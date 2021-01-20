(ns minitest.higher-order
  (:require [net.cgrand.macrovich       :as           macros]
            [minitest.config #?@(:clj  [:refer        [context
                                                       config
                                                       with-context
                                                       with-config]]
                                 :cljs [:refer        [context
                                                       config]
                                        :refer-macros [with-context
                                                       with-config]])])
  #?(:cljs (:require-macros
             [minitest.higher-order     :refer        [def-on-fn]])))

(macros/deftime
  (defmacro def-on-fn [name first-arg pred-expr]
    (let [f-sym        (gensym "f")
          continue-sym (gensym "continue")]
      `(defn ~name [~first-arg ~f-sym & [~continue-sym]]
         (fn
           ~@(for [args '[;; This arity for orchestrate-fn
                          [&state &level &ns &data]
                          ;; This arity for run-fn, execute-fn & report-fn
                          [&state &position &level &ns &data]]]
               `(~args
                  (let [~'&position ~(if (.contains args '&position)
                                       '&position
                                       nil)]
                    (if ~pred-expr
                      (let [new-data# (~f-sym ~@args)]
                        (if ~continue-sym
                          (~continue-sym ~@(butlast args) new-data#)
                          new-data#))
                      (if ~continue-sym
                        (~continue-sym ~@args)
                        ~'&data)))))))))

  (defmacro anaph| [f]
    `(fn
       ([      ~'&state             ~'&level ~'&ns ~'&data]
        (~f    ~'&state             ~'&level ~'&ns ~'&data))
       ([      ~'&state ~'&position ~'&level ~'&ns ~'&data]
        (~f    ~'&state ~'&position ~'&level ~'&ns ~'&data))))

  (defmacro outside-in->> [& forms]
    `(->> ~@(reverse forms))))

;; For cljs
(declare on-level|)
(declare on-context|)
(declare on-config|)

(def-on-fn on-level|   position-level (cond
                                        (set? position-level)
                                        (position-level [&position &level])
                                        (fn? position-level)
                                        (position-level &state &position &level
                                                        &ns &data)
                                        :else
                                        (= position-level [&position &level])))
(def-on-fn on-context| expected-ctx   (= expected-ctx
                                         (select-keys (context)
                                                      (keys expected-ctx))))
(def-on-fn on-config|  expected-cfg   (= expected-cfg
                                         (select-keys (config)
                                                      (keys expected-cfg))))

(defmacro with-config|  [ctx f] `(anaph| #(with-config  ~ctx (apply ~f %&))))
(defmacro with-context| [ctx f] `(anaph| #(with-context ~ctx (apply ~f %&))))

