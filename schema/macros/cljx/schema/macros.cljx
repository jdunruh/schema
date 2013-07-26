(ns schema.macros
  (:refer-clojure :exclude [defrecord defn fn])
  (:require [clojure.data :as data]))

;;;;; Schema protcol

;; TODO: rename to be more platform agnostic

(defmacro error! [& format-args]
  #+clj `(throw (IllegalArgumentException. (format ~@format-args)))
  #+cljs `(throw js/Error (format ~@format-args)))

(defmacro assert-iae
  "Like assert, but throws an IllegalArgumentException and takes args to format"
  [form & format-args]
  `(when-not ~form
     (error! ~@format-args)))

(defmacro validation-error [schema value expectation]
  `(ValidationError. ~schema ~value (delay ~expectation)))

(clojure.core/defn maybe-split-first [pred s]
  (if (pred (first s))
    [(first s) (next s)]
    [nil s]))

(clojure.core/defn looks-like-a-protocol-var?
  "There is no 'protocol?'in Clojure, so here's a half-assed attempt."
  [v]
  (and (var? v)
       (map? @v)
       (= (:var @v) v)
       (:on @v)))

(clojure.core/defn fix-protocol-tag [env tag]
  (or (when (symbol? tag)
        (when-let [v (resolve env tag)]
          (when (looks-like-a-protocol-var? v)
            `(schema.core/protocol (deref ~v)))))
      tag))


;; TODO(ah) copy from plumbing
(clojure.core/defn assoc-when
  "Like assoc but only assocs when value is truthy"
  [m & kvs]
  (assert (even? (count kvs)))
  (into (or m {})
        (for [[k v] (partition 2 kvs)
              :when v]
          [k v])))

(defmacro lazy-get
  "Like get but lazy about default"
  [m k d]
  `(if-let [pair# (find ~m ~k)]
     (val pair#)
     ~d))

(clojure.core/defn safe-get
  "Like get but throw an exception if not found"
  [m k]
  (lazy-get m k (error! "Key %s not found in %s" k m)))

(def primitive-sym? '#{float double boolean byte char short int long
                       floats doubles booleans bytes chars shorts ints longs objects})

(clojure.core/defn valid-tag? [env tag]
  (and (symbol? tag) (or (primitive-sym? tag) (class? (resolve env tag)))))

(clojure.core/defn normalized-metadata
  "Take an object with optional metadata, which may include a :tag and/or explicit
   :schema/:s/:s?/:tag data, plus an optional explicit schema, and normalize the
   object to have a valid Clojure :tag plus a :schema field."
  [env imeta explicit-schema]
  (let [{:keys [tag s s? schema]} (meta imeta)]
    (assert-iae (< (count (remove nil? [s s? schema explicit-schema])) 2)
                "Expected single schema, got meta %s, explicit %s" (meta symbol) explicit-schema)
    (let [schema (fix-protocol-tag
                  env
                  (or s schema (when s? `(schema.core/maybe ~s?)) explicit-schema tag `schema.core/Top))]
      (with-meta imeta
        (-> (or (meta imeta) {})
            (dissoc :tag :s :s? :schema)
            (assoc-when :schema schema
                        :tag (let [t (or tag schema)]
                               (when (valid-tag? env t)
                                 t))))))))


(clojure.core/defn extract-arrow-schematized-element
  "Take a nonempty seq, which may start like [a ...] or [a :- schema ...], and return
   a list of [first-element-with-schema-attached rest-elements]"
  [env s]
  (assert (seq s))
  (let [[f & more] s]
    (if (= :- (first more))
      [(normalized-metadata env f (second more)) (drop 2 more)]
      [(normalized-metadata env f nil) more])))

(clojure.core/defn process-arrow-schematized-args
  "Take an arg vector, in which each argument is followed by an optional :- schema,
   and transform into an ordinary arg vector where the schemas are metadata on the args."
  [env args]
  (loop [in args out []]
    (if (empty? in)
      out
      (let [[arg more] (extract-arrow-schematized-element env in)]
        (recur more (conj out arg))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schematized defrecord


(clojure.core/defn extract-schema-form
  "Pull out the schema stored on a thing.  Public only because of its use in a public macro."
  [symbol]
  (let [s (:schema (meta symbol))]
    (assert-iae s "%s is missing a schema" symbol)
    s))


(defmacro defrecord
  "Define a defrecord 'name' using a modified map schema format.

   field-schema looks just like an ordinary defrecord field binding, except that you
   can use ^{:s/:schema +schema+} forms to give non-primitive, non-class schema hints
   to fields.
   e.g., [^long foo  ^{:schema {:a double}} bar]
   defines a record with two base keys foo and bar.
   You can also use ^{:s? schema} as shorthand for {:s (maybe schema)},
   or ^+schema+ to refer to a var/local defining a schema (note that this form
   is not legal on an ordinary defrecord, however, unlike all the others).

   extra-key-schema? is an optional map schema that defines additional optional
   keys (and/or a key-schemas) -- without it, the schema specifies that extra
   keys are not allowed in the record.

   extra-validator-fn? is an optional additional function that validates the record
   value.

   and opts+specs is passed through to defrecord, i.e., protocol/interface
   definitions, etc."
  {:arglists '([name field-schema extra-key-schema? extra-validator-fn? & opts+specs])}
  [name field-schema & more-args]
  (let [[extra-key-schema? more-args] (maybe-split-first map? more-args)
        [extra-validator-fn? more-args] (maybe-split-first (complement symbol?) more-args)
        field-schema (process-arrow-schematized-args &env field-schema)]
    `(do
       (when-let [bad-keys# (seq (filter #(schema.core/required-key? %)
                                         (keys ~extra-key-schema?)))]
         (throw (RuntimeException. (str "extra-key-schema? can not contain required keys: "
                                        (vec bad-keys#)))))
       (when ~extra-validator-fn?
         (assert-iae (fn? ~extra-validator-fn?) "Extra-validator-fn? not a fn: %s"
                     (class ~extra-validator-fn?)))
       (potemkin/defrecord+ ~name ~field-schema ~@more-args)
       (schema.core/declare-class-schema!
        ~name
        (assoc-when
         (schema.core/record ~name (merge ~(into {}
                                                 (for [k field-schema]
                                                   [ (keyword (clojure.core/name k))
                                                     (do (assert-iae (symbol? k)
                                                                     "Non-symbol in record binding form: %s" k)
                                                         (extract-schema-form k))]))
                                          ~extra-key-schema?))
         :extra-validator-fn ~extra-validator-fn?))
       ~(let [map-sym (gensym "m")]
          `(clojure.core/defn ~(symbol (str 'map-> name))
             ~(str "Factory function for class " name ", taking a map of keywords to field values, but not 400x"
                   " slower than ->x like the clojure.core version")
             [~map-sym]
             (let [base# (new ~(symbol (str name))
                              ~@(map (clojure.core/fn [s] `(get ~map-sym ~(keyword s))) field-schema))
                   remaining# (dissoc ~map-sym ~@(map keyword field-schema))]
               (if (seq remaining#)
                 (merge base# remaining#)
                 base#))))
       ~(let [map-sym (gensym "m")]
          `(clojure.core/defn ~(symbol (str 'strict-map-> name))
             ~(str "Factory function for class " name ", taking a map of keywords to field values.  All"
                   " keys are required, and no extra keys are allowed.  Even faster than map->")
             [~map-sym]
             (when-not (= (count ~map-sym) ~(count field-schema))
               (throw (RuntimeException. (format "Record has wrong set of keys: %s"
                                                 (data/diff (set (keys ~map-sym))
                                                            ~(set (map keyword field-schema)))))))
             (new ~(symbol (str name))
                  ~@(map (clojure.core/fn [s] `(safe-get ~map-sym ~(keyword s))) field-schema)))))))

(defmacro with-fn-validation [& body]
  `(do (.set_cell schema.core/use-fn-validation true)
       ~@body
       (.set_cell schema.core/use-fn-validation false)))

(clojure.core/defn split-rest-arg [bind]
  (let [[pre-& post-&] (split-with #(not= % '&) bind)]
    (if (seq post-&)
      (do (assert-iae (= (count post-&) 2) "Got more than 1 symbol after &: %s" (vec post-&))
          (assert-iae (symbol? (second post-&)) "Got non-symbol after & (currently unsupported): %s" (vec post-&))
          [(vec pre-&) (last post-&)])
      [bind nil])))

(clojure.core/defn single-arg-schema-form [[index arg]]
  `(schema.core/one
    ~(extract-schema-form arg)
    ~(if (symbol? arg)
       (name arg)
       (str "arg" index))))

(clojure.core/defn rest-arg-schema-form [arg]
  (let [s (extract-schema-form arg)]
    (if (= s `schema.core/Top)
      [`schema.core/Top]
      (do (assert-iae (vector? s) "Expected seq schema for rest args, got %s" s)
          s))))

(clojure.core/defn input-schema-form [regular-args rest-arg]
  (let [base (mapv single-arg-schema-form (map-indexed vector regular-args))]
    (if rest-arg
      (vec (concat base (rest-arg-schema-form rest-arg)))
      base)))


(clojure.core/defn process-fn-arity
  "Process a single (bind & body) form, producing an output tag, schema-form,
   and arity-form which has asserts for validation purposes added that are
   executed when turned on, and have very low overhead otherwise.
   tag? is a prospective tag for the fn symbol based on the output schema.
   schema-bindings are bindings to lift eval outwards, so we don't build the schema
   every time we do the validation."
  [env output-schema-sym bind-meta [bind & body]]
  (assert-iae (vector? bind) "Got non-vector binding form %s" bind)
  (when-let [bad-meta (seq (filter (or (meta bind) {}) [:tag :s? :s :schema]))]
    (throw (RuntimeException. (str "Meta not supported on bindings, put on fn name" (vec bad-meta)))))
  (let [bind (with-meta (process-arrow-schematized-args env bind) bind-meta)
        [regular-args rest-arg] (split-rest-arg bind)
        input-schema-sym (gensym "input-schema")]
    {:schema-binding [input-schema-sym (input-schema-form regular-args rest-arg)]
     :arity-form (if true
                   (let [bind-syms (vec (repeatedly (count regular-args) gensym))
                         metad-bind-syms (with-meta (mapv #(with-meta %1 (meta %2)) bind-syms bind) bind-meta)]
                     (list
                      (if rest-arg
                        (-> metad-bind-syms (conj '&) (conj rest-arg))
                        metad-bind-syms)
                      `(let ~(vec (interleave (map #(with-meta % {}) bind) bind-syms))
                         (let [validate# (.get_cell ~'ufv)]
                           (when validate#
                             (schema.core/validate
                              ~input-schema-sym
                              ~(if rest-arg
                                 `(list* ~@bind-syms ~rest-arg)
                                 bind-syms)))
                           (let [o# (do ~@body)]
                             (when validate# (schema.core/validate ~output-schema-sym o#))
                             o#)))))
                   (cons bind body))}))

(clojure.core/defn process-fn-
  "Process the fn args into a final tag proposal, schema form, schema bindings, and fn form"
  [env name fn-body]
  (let [output-schema (extract-schema-form name)
        output-schema-sym (gensym "output-schema")
        bind-meta (or (when-let [t (:tag (meta name))]
                        (when (primitive-sym? t)
                          {:tag t}))
                      {})
        processed-arities (map (partial process-fn-arity env output-schema-sym bind-meta)
                               (if (vector? (first fn-body))
                                 [fn-body]
                                 fn-body))
        schema-bindings (map :schema-binding processed-arities)
        fn-forms (map :arity-form processed-arities)]
    {:schema-bindings (vec (apply concat [output-schema-sym output-schema] schema-bindings))
     :schema-form `(schema.core/make-fn-schema ~output-schema-sym ~(mapv first schema-bindings))
     :fn-form `(let [^schema.core.PSimpleCell ~'ufv schema.core/use-fn-validation]
                 (clojure.core/fn ~name
                   ~@fn-forms))}))

(defn- parse-arity-spec [spec]
  (assert-iae (vector? spec) "An arity spec must be a vector")
  (let [[init more] ((juxt take-while drop-while) #(not= '& %) spec)
        fixed (mapv (clojure.core/fn [i s] `(schema.core/one ~s ~(str "arg" i))) (range) init)]
    (if (empty? more)
      fixed
      (do (assert-iae (and (= (count more) 2) (vector? (second more)))
                      "An arity with & must be followed by a single sequence schema")
          (into fixed (second more))))))

(defmacro =>*
  "Produce a function schema from an output schema and a list of arity input schema specs,
   each of which is a vector of argument schemas, ending with an optional '& more-schema'
   specification where more-schema must be a sequence schema.

   Currently function schemas are purely descriptive; there is no validation except for
   functions defined directly by s/fn or s/defn"
  [output-schema & arity-schema-specs]
  `(schema.core/make-fn-schema ~output-schema ~(mapv parse-arity-spec arity-schema-specs)))

(defmacro =>
  "Convenience function for defining functions with a single arity; like =>*, but
   there is no vector around the argument schemas for this arity."
  [output-schema & arg-schemas]
  `(=>* ~output-schema ~(vec arg-schemas)))

(ns-unmap *ns* 'fn)

(defmacro fn
  "Like clojure.core/fn, except that schema-style typehints can be given on the argument
   symbols and on the arguemnt vector (for the return value), and (for now)
   schema metadata is only processed at the top-level.  i.e., you can use destructuring,
   but you must put schema metadata on the top level arguments and not on the destructured
   shit.  The only unsupported form is the '& {}' map destructuring.

   This produces a fn that you can call fn-schema on to get a schema back.
   This is currently done using metadata for fns, which currently causes
   clojure to wrap the fn in an outer non-primitive layer, so you may pay double
   function call cost and lose the benefits of primitive type hints.

   When compile-fn-validation is true (at compile-time), also automatically
   generates pre- and post-conditions on each arity that validate the input and output
   schemata whenever *use-fn-validation* is true (at run-time)."
  [& fn-args]
  (let [[name more-fn-args] (if (symbol? (first fn-args))
                              (extract-arrow-schematized-element &env fn-args)
                              ["fn" fn-args])
        {:keys [schema-bindings schema-form fn-form]} (process-fn- &env name more-fn-args)]
    `(let ~schema-bindings
       (with-meta ~fn-form ~{:schema schema-form}))))

(defmacro defn
  "defn : clojure.core/defn :: fn : clojure.core/fn.

   Things of note:
    - Unlike clojure.core/defn, we don't support a final attr-map on multi-arity functions
    - The '& {}' map destructing form is not supported
    - fn-schema works on the class of the fn, so primitive hints are supported and there
      is no overhead, unlike with 'fn' above
    - Output metadata always goes on the argument vector.  If you use the same bare
      class on every arity, this will automatically propagate to the tag on the name."
  [& defn-args]
  (let [[name more-defn-args] (extract-arrow-schematized-element &env defn-args)
        [doc-string? more-defn-args] (maybe-split-first string? more-defn-args)
        [attr-map? more-defn-args] (maybe-split-first map? more-defn-args)
        {:keys [schema-bindings schema-form fn-form]} (process-fn- &env name more-defn-args)]
    `(let ~schema-bindings
       (def ~(with-meta name
               (assoc-when (or attr-map? {})
                           :doc doc-string?
                           :schema schema-form
                           :tag (let [t (:tag (meta name))]
                                  (when-not (primitive-sym? t)
                                    t))))
         ~fn-form)
       (schema.core/declare-class-schema! (class ~name) ~schema-form))))
