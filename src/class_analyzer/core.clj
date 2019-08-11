(ns class-analyzer.core
  (:import [java.io DataInputStream]
           [java.util.jar JarEntry])
  (:require [clojure.java.io :as io :refer [file]]))


(set! *warn-on-reflection* true)


(defmacro ^:private  str! [x] `(doto ~x (assert ~(str "Not string: " (pr-str x)))))


(defn- jar-entries [jar-path]
  (assert (.exists (file jar-path))
          (str "No such file: " (file jar-path)))
  (let [jar-file (new java.util.jar.JarFile (file jar-path) false)]
    (enumeration-seq (.entries jar-file))))


(defn- jar-classes [jar-path]
  (for [^JarEntry entry (jar-entries jar-path)
        :let [entry-name (.getName entry)]
        :when (.endsWith entry-name ".class")]
    (-> entry-name
        (.substring 0 (- (count entry-name) 6))
        (.replace "/" ".")
        ; (.replace "$" ".")
        )))


;; https://medium.com/@davethomas_9528/writing-hello-world-in-java-byte-code-34f75428e0ad
;; https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html


(defn- read-str [^DataInputStream is len]
  (let [ba (byte-array len)]
    ;; TODO: use some looping here!
    (loop [read-len 0]
      (if (= read-len len)
        (new String ba)
        (recur (+ read-len (.read is ba read-len (- len read-len))))))))


(defn- constant-pool-record [^DataInputStream ois]
  (case (.readUnsignedByte ois)
    7  [:class 3 (.readUnsignedShort ois)] ;; tag + name idx
    9  [:fieldref 5 [(.readUnsignedShort ois) (.readUnsignedShort ois)]] ;; tag + classidx + nameandtype idx
    10 [:methodref 5 [(.readUnsignedShort ois) (.readUnsignedShort ois)]] ;; tag + class idx + nameandtype idx
    8  [:string 3 (.readUnsignedShort ois)]

    3  [:integer (.readInt ois)] ;; int, short, char, byte, boolean
    4  [:float   (.readFloat ois)]
    5  [:long (.readLong ois)]
    6  [:double (.readDouble ois)]
    12 [:nameandtype 5 [(.readUnsignedShort ois)
                        (.readUnsignedShort ois)]] ;; tag nameidx descriptoridx

    1  (let [length (.readUnsignedShort ois)]
         [:utf8 (+ 3 length) (read-str ois length)])

    11 [:interfacemethodred 5 [(.readUnsignedShort ois) (.readUnsignedShort ois)]] ;; tag + class idx + nameandtypeidx

    15 [:methodhandle [(.readUnsignedByte ois) (.readUnsignedShort ois)]]
    16 [:methodtype (.readUnsignedShort ois)]

    ;; TODO!!!
    ;;18 [:invokedynamic 0]
    ))


(defn constant-pool [^DataInputStream ois]
  (let [cp-size (.readUnsignedShort ois)]
    (loop [acc (sorted-map)
           i 1]
      (if (< i cp-size)
        (let [[disc size content] (constant-pool-record ois)]
          (recur (assoc acc i {:discriminator disc, :data content}) (inc i)))
        acc))))


(defn- cp-enhance-1 [pool]
  (reduce-kv
   (fn [acc k v]
     (case (:discriminator v)
       (:class :string) (update-in acc [k :data] (comp :data acc))

       :nameandtype (update acc k merge {:name (-> v :data first acc :data str!)
                                         :type (-> v :data second acc :data str!)})
       acc))
   pool pool))


(defn- cp-enhance-2 [pool]
  (reduce-kv
   (fn [acc k v]
     (case (:discriminator v)
       (:fieldref :methodref :interfacemethodred)
       (update acc k merge {:class (-> v :data first acc :data str!)
                            :name  (-> v :data second acc :name str!)
                            :type (-> v :data second acc :type str!)})
       acc))
   pool pool))


(def read-attribute nil)


(defmulti read-attribute (fn [input-stream name length constant-pool] name))


(defmethod read-attribute :default [^DataInputStream dis _ cnt _]
  (dotimes [_ cnt] (.readByte dis))
  :not-parsed)


(defmethod read-attribute "SourceFile" [^java.io.DataInputStream dis _ _ constant-pool]
  (-> dis .readUnsignedShort constant-pool :data))


(defn read-attributes [^java.io.DataInputStream ois constant-pool]
  (let [attr-cnt (.readUnsignedShort ois)]
    (doall
     (for [i (range attr-cnt)
           :let [attr-name (-> ois .readUnsignedShort constant-pool :data #_str!)
                 attr-len (.readInt ois)
                 attr     (read-attribute ois attr-name attr-len constant-pool)]]
       {:name  attr-name
        :value attr}))))


(defn parse-access-flags [n]
  {:public    (bit-test n 0)
   :final     (bit-test n 3)
   :super     (bit-test n 4) ;; Not final, can be extended
   :interface (bit-test n 9 )
   :abstract  (bit-test n 10)
   :synthetic (bit-test n 12) ;; generated
   :annotation (bit-test n 13)
   :enum       (bit-test n 14)})


(defn- read-methods [^java.io.DataInputStream ois constant-pool]
  (let [method-cnt (.readUnsignedShort ois)]
    (doall
     (for [i (range method-cnt)
           :let [access-flags (.readUnsignedShort ois)
                 name-idx     (.readUnsignedShort ois)
                 descr-idx    (.readUnsignedShort ois)
                 attrs        (read-attributes ois constant-pool)]]
       {:access (parse-access-flags access-flags)
        :name   (-> name-idx constant-pool :data)
        :descr  (-> descr-idx constant-pool :data)
        :attrs  attrs}))))


(defn- read-interfaces [^DataInputStream ois constant-pool]
   (let [iface-cnt (.readUnsignedShort ois)]
     (doall
      (for [i (range iface-cnt)
            :let [class-idx (.readUnsignedShort ois)]]
        (-> class-idx constant-pool :data)))))


(defn- read-entry [zis]
  (let [ois (new java.io.DataInputStream zis)
        read-int (fn [] (.readUnsignedShort ois))
        read-str (partial read-str ois)]

    ;; magic number
    (assert (= 51966 (.readUnsignedShort ois))) ;; CA FE
    (assert (= 47806 (.readUnsignedShort ois))) ;; BA BE

    (let [[minor major] [(read-int) (read-int)]

          pool          (-> ois constant-pool cp-enhance-1 cp-enhance-2)
          access-flags  (.readUnsignedShort ois)
          class         (-> ois .readUnsignedShort pool :data str!)
          super-class   (-> ois .readUnsignedShort pool :data str!)
          interfaces    (read-interfaces ois pool)

          nr-fields (.readUnsignedShort ois)
          _         (assert (zero? nr-fields))
          ;; TODO - implement field reading here!

          methods (read-methods ois pool)
          attrs   (read-attributes ois pool)]
      {:version {:minor minor :major major}
       :class       class
       :super-class super-class
       :interfaces  interfaces
       :attributes  attrs
       :methods     methods
       :access      (parse-access-flags access-flags)
       :constants   pool})))


                                        ; (def example-jar "/home/erdos/.m2/repository/commons-io/commons-io/2.6/commons-io-2.6.jar")
(def example-jar "/home/erdos/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar")


(with-open [fis (new java.io.FileInputStream (file example-jar))
            zis (new java.util.zip.ZipInputStream fis)]
  (doseq [i (range 10000)
          :let [entry (.getNextEntry zis)]
          :while (some? entry)
          :when (.contains (.getName entry) "IPersistentM")
          ]
    (clojure.pprint/pprint (read-entry zis))))

(jar-classes example-jar)

(def entry-0 (first (filter #(.contains (.getName ^JarEntry %) "ByteOrderParser") (jar-entries example-jar))))