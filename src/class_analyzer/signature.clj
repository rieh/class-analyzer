(ns class-analyzer.signature
  "Recursive Descent Parser for JVM signatures."
  (:import [java.io Reader BufferedReader StringReader PushbackReader]))

(set! *warn-on-reflection* true)

(def ^{:dynamic true :tag PushbackReader} *reader* nil)

(defmacro with-str [s & bodies]
  `(binding [*reader* (PushbackReader. (StringReader. ~s))] ~@bodies))

(defn- expect
  ([s] (expect s s))
  ([s return]
   (assert (char? s))
   (let [r (.read *reader*)]
     (when-not (neg? r)
       (if (= (char r) (char s))
         return
         (do (.unread *reader* r) nil))))))

;; returns a read identifier or nil
(defn identifier
  "Reads a Java identifier as a String from the Reader. Returns nil and reverts when not found."
  []
  (let [first-char (.read *reader*)]
    (when (neg? first-char)
      (throw (ex-info "Unexpected end of stream!" {})))
    (if (Character/isJavaIdentifierStart first-char)
      (let [sb (new StringBuilder)]
        (.append sb (char first-char))
        (loop []
          (let [next-char (.read *reader*)]
            (if (Character/isJavaIdentifierPart next-char)
              (do
                (.append sb (char next-char))
                (recur))
              (do (.unread *reader* next-char)
                  (str sb))))))
      (do (.unread *reader* first-char) nil))))

(defn- either [& reader-functions] (some #(%) reader-functions))

(defn- wildcard-indicator [] (or (expect \+ :extends) (expect \- :super)))

(defn- expect! [s]
  (assert (expect s)
          (str "Could not read " s ", suffix: " (slurp *reader*))))

(defn- separated-tokens-1 [separator-char reader-fn]
  (when-let [fst (reader-fn)]
    (loop [result [fst]]
      (if (expect separator-char)
        (if-let [nxt (reader-fn)]
          (recur (conj result nxt))
          (throw (ex-info "Could not read" {:reader-fn reader-fn})))
        result))))

(defn- repeat+ [reader-fn]
  (when-let [fst (reader-fn)]
    (loop [result [fst]]
      (if-let [nxt (reader-fn)]
        (recur (conj result nxt))
        result))))

(defn- repeat* [reader-fn]
  (vec (repeat+ reader-fn)))

(defn- wrapped [before-char inside-fn after-char]
  (when (expect before-char)
    (let [result (inside-fn)]
      (assert result)
      (assert (expect after-char) (str "rest: " (slurp *reader*)))
      result)))

(declare field-type-signature)

(defn- class-bound []
  (when (expect \:)
    (field-type-signature))) ;; optional

(defn- interface-bound []
  (when (expect \:)
    (doto (field-type-signature)
      (assert)))) ;; not optional.

;; identifier classbound interfacebound*
(defn- formal-type-parameter []
  (when-let [id (identifier)]
    (let [classbound (class-bound)
          interfacebound (repeat* interface-bound)]
      {:identifier id
       :classbound classbound
       :interfacebound interfacebound})))

;;
(defn render-formal-type-parameter [ftp]
  (assert (:identifier ftp))
  (assert (:classbound ftp))
  (assert (:interfacebound ftp))
  ;; TODO: also use classbound, interfacebound...
  (:identifier ftp))

;; < FormalTypeParameter+ >
(defn- formal-type-parameters []
  (wrapped \< #(repeat+ formal-type-parameter) \>))

(declare class-type-signature array-type-signature type-variable-signature type-signature base-type)

(defn- base-type []
  (or (expect \B :byte)
      (expect \C :char)
      (expect \D :double)
      (expect \F :float)
      (expect \I :int)
      (expect \J :long)
      (expect \S :short)
      (expect \Z :boolean)))

;; L - reference
;; [ - array of one dimension

(defn- type-variable-signature []
  (wrapped \T identifier \;))

(defn field-type-signature []
  (either class-type-signature array-type-signature type-variable-signature))

(defn- type-signature [] (either field-type-signature base-type))

;; https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.3.2
(defn field-descriptor []
  (letfn [(field-type [] (either base-type object-type array-type))
          (object-type [] (wrapped \L class-name \;))
          (array-type [] (when (expect \[) (some->> (component-type) (hash-map :array))))
          (component-type [] (field-type))
          (class-name []
            (let [pkgs+id  (separated-tokens-1 \/ identifier)
                  pkgs     (pop pkgs+id)
                  id       (peek pkgs+id)]
              (assert pkgs+id)
              {:package (clojure.string/join "." (pop pkgs+id))
               :class   (peek pkgs+id)}))]
    (field-type)))

(defn- array-type-signature []
  (when (expect \[)
    (let [ts (type-signature)]
      (assert ts)
      {:array ts})))

(defn- class-type-signature []
  (letfn [(type-argument []
            (or (expect \*)
                (let [ind? (wildcard-indicator)
                      fts  (field-type-signature)]
                  (when ind? (assert fts))
                  (when fts
                    {:indicator ind?
                     :field-type-signature fts}))))
          (type-arguments []
            (wrapped \< #(repeat+ type-argument) \>))]
    (when (expect \L)
      (let [pkgs+id  (separated-tokens-1 \/ identifier)
            pkgs     (pop pkgs+id)
            id       (peek pkgs+id)
            id-type? (type-arguments) ;; opt
            sufs (repeat* #(when (expect \.)
                             (hash-map :id (identifier)
                                       :type-arg (type-argument))))]
        (expect! \;)
        (cond->
            {:package (clojure.string/join "." pkgs)
             :class id}
          id-type? (assoc :generic id-type?)
          (seq sufs) (assoc :sufs sufs))))))

(def super-class-signature class-type-signature)
(def super-interface-signature class-type-signature)

;; "FormalTypeParameters_opt SuperclassSignature SuperinterfaceSignature*"
(defn class-signature []
  (let [fts (formal-type-parameters)]
    (if-let [superclass (super-class-signature)]
      (let [is (repeat* super-interface-signature)]
        {:formal-type-parameters fts
         :superclass superclass
         :superinterface is})
      (if fts (assert false)))))

;; VoidDescriptor: V
(defn- void-descriptor [] (and (expect \V) :void))

;; ReturnType: TypeSignature OR VoidDescriptor
(defn- return-type [] (either type-signature void-descriptor))

;; ThrowsSignature: ^ ClassTypeSignature     OR   ^ TypeVariableSignature
(defn- throws-signature []
  (when (expect \^)
    (doto (either class-type-signature type-variable-signature)
      (assert))))

;; MethodTypeSignature: FormalTypeParametersopt (TypeSignature*) ReturnType ThrowsSignature*
(defn method-type-signature []
  (let [fts (formal-type-parameters)]
    (if-not (expect \()
      (assert (nil? fts))

      (let [ts (repeat* type-signature)]
        (expect! \))
        (let [rs (return-type)
              ths (repeat* throws-signature)]
          (assert rs)
          {:type-params fts
           :args   (vec ts)
           :return rs
           :throws ths})))))

(defn render-type [t]
  (cond (keyword? t) (name t)
        (:array t)   (str (render-type (:array t)) "[]")
        (:package t) (str (:package t) "." (:class t))
        :else        (throw (ex-info "Unknown type" {:type t}))))
