(ns minitest
  #?(:clj (:gen-class))
  (:require [clojure.string                    :as           str]
            [clojure.pprint                    :refer        [pprint]]
   #?(:clj  [clojure.spec.alpha                :as           s])
   #?(:clj  [clojure.tools.namespace.repl      :refer        [disable-reload!]])
   #?(:clj  [clojure.repl                      :refer        [source-fn]])
   #?(:clj  [clojure.edn                       :as           edn])
   #?(:clj  [clojure.core.unify                :refer        [unify]])
            [net.cgrand.macrovich              :as           macros]
            [minitest.ns                       :as           ns
                             #?@(:cljs [       :include-macros true])]
            [minitest.base-config              :refer        [base-config]]
            [minitest.runner                   :refer        [run
                                                              *running-inner-tests*]]
            [minitest.executor                 :refer        [execute-clj
                                                              execute-cljs]]
            [minitest.reporter                 :refer        [report]]
            [minitest.orchestrator             :refer        [orchestrate
                                                              run-execute-report!]]
            [minitest.config                   :as           config
                             #?@(:cljs [       :include-macros true])]
            [minitest.utils  #?@(:clj  [       :refer        [as-form
                                                              as-thunk
                                                              as-wildcard-thunk
                                                              ->|
                                                              current-bindings
                                                              meta-macroexpand
                                                              current-ns-name
                                                              defalias]]
                                 :cljs [       :refer        [as-form
                                                              as-thunk
                                                              as-wildcard-thunk
                                                              ->|
                                                              current-bindings]
                                               :refer-macros [current-ns-name
                                                               defalias]])]
            [minitest.around-load   #?@(:clj  [:refer        [apply-clj-patches
                                                              store-or-run-tests!
                                                              process-after-load!
                                                              *tests*
                                                              currently-loading?]]
                                        :cljs [:refer        [store-or-run-tests!
                                                              process-after-load!
                                                              *tests*]
                                               :refer-macros [currently-loading?]])]))

(macros/deftime (disable-reload!))
(macros/deftime (macros/case :clj (apply-clj-patches)))

; (defalias config       minitest.config/config)
; (defalias context      minitest.config/context)
; (defalias with-config  minitest.config/with-config)
; (defalias with-context minitest.config/with-context)

;; -- Dev tools
;; ---- Some commands
;; fswatch src/!(minitest.cljc) | (while read; do touch src/minitest.cljc; done)
;;
;; (cljs/build "test" {:main 'minitest-test :output-to "compiled.js" :output-dir "out" :optimizations :simple :target :nodejs})
;;
;; (require 'shadow.cljs.devtools.server) (shadow.cljs.devtools.server/start!) (require '[shadow.cljs.devtools.api :as shadow]) (shadow/watch :browser-support)


;; -- Explorations
; TODO: disable warnings with cljs.analyzer.api/no-warn
; See:  https://github.com/clojure/clojurescript/blob/5e88d3383e0f950c4de410d3d6ee11769f3714f4/src/main/clojure/cljs/analyzer/api.cljc#L140

;; TODO: *load-test*
;; See:  https://github.com/clojure/clojurescript/blob/5e88d3383e0f950c4de410d3d6ee11769f3714f4/src/main/clojure/cljs/analyzer.cljc#L61


;; DONE
;; ## When and how to run tests
;; - [√] tests are run once
;; - [√] tests have absolutely no impact (i.e. the macro expands
;;       to nothing) when configured for a production environment.
;; - [√] tests are registered and/or run at load time or with eval according
;;       to the config.
;; - [√] tests are run once every var has loaded to avoid introduce code
;;       ordering problems to the already overwhelmed programmer.
;; - [√] `test!` can be used at load time to run the tests registered so far.
;; - [ ] works well with reload and var unloading (clojure.tools.namespace)
;; - [√] when tests are run via the clj test runner or explicitly in the repl
;;       with the test! fn:
;;       - successes: reported.
;;       - failures:  reported.
;; - [√] when tests are *implicitly* run from the repl:
;;       - successes: silenced.
;;       - failures: reported.
;; - [√] effects can be run by putting '!!' before an expr, evaluating it
;;       without testing the result.

;; ## Test selectors
;; - The CLI runner should:
;;   - [√] run all tests if no args are provided
;;   - [√] otherwise proceed with a:
;;         - whitelist logic using one or more namespace selectors (the args):
;;           - [√] a ns name
;;           - [√] a ns glob ("my.ns.*")
;;           - [√] a regex
;;           - [√] a predicate fn
;;         - and a blacklist logic using these same selectors but:
;;           - [x] prefixed with "!" (ns name & ns globs only).
;;                 Clashes with bash special chars.
;;           - [√] or by providing a sequence to an ":exclude" option
;; - The test! fn behaves the same but when no args are provided:
;;   - [√] it runs tests for the local namespace if it possesses minitest tests.
;;   - [√] it runs all the tests otherwise (for instance in the REPL from the
;;         "user" namespace).

;; ## Config
;; - Options:
;;   - [√] :fail-fast.
;;   - [ ] :break-on-failure (like https://github.com/ConradIrwin/pry-rescue).
;;   - [√] :silent-success.
;; - [√] a default config for each environment (CLI, REPL, on-load).
;; - [√] which can be overriden in a project's minitest.edn file
;; - [x] which can be overriden by ENV_VARS
;; - [√] which can be overriden by args passed to the test! fn or the CLI Runner

;; ## Report format
;; - [ ] JUnit (a bit more work for a bit more readability in CIs, especially with
;;       lot of tests).
;; - [√] configurable test output


;; ## More
;; - [x] NO. Warning on {:exec-mode {:on-eval {:store-tests true}}}
;;       (stores duplicate tests)
;; - [ ] Check exec mode of CLI runner
;; - [√] Clarify config names
;; - [ ] Config map init-fn
;; - [√] set-context!
;; - [√] namespaces as context

(defn minitest-var? [var]
  (when-let [s (some->> var meta :ns ns-name str)]
    (and      (re-matches #"minitest.*" s)
         (not (re-matches #"minitest.*-test" s)))))

(defn case-config-bindings []
  (->> [#'config/*early-config*
        #'config/*late-config*
        #'config/*context*]
       (map (->| (juxt identity deref) vec))
       (into {})))

(defn case-bindings []
  (let [config-var? (set (keys (case-config-bindings)))]
    (->> (current-bindings)
         (remove (->| key (some-fn config-var? minitest-var?
                                   #{#'*1 #'*2 #'*3 #'*e})))
         (into {}))))

(macros/deftime
  ;; TODO: too much
  (defn conform! [spec value]
    (let [result (s/conform spec value)]
      (when (= result :s/invalid)
        (throw (Exception.
                 (binding [*print-level* 7
                           *print-namespace-maps* false]
                   (str \newline
                        (with-out-str (->> (s/explain-data spec value)
                                           (mapv (fn [[k v]]
                                                   [(-> k name keyword) v]))
                                           (into {})
                                           pprint)))))))
      result))

  (defn- parse-tests [env block-body]
    (let [not-op? (complement #{:= :?})]
      (->> block-body
           (conform!
             (s/*
               (s/alt :effect  not-op?
                      :expectation (s/alt
                                     := (s/cat :tested   not-op?
                                               :op       #{:=}
                                               :expected not-op?)
                                     :? (s/cat :op       #{:?}
                                               :tested not-op?)))))
           ;; Sample of what we are processing next:
           ;; [[:effect '(do :something)]
           ;;  [:expectation [:= {:tested 1, :op :=, :expected 1}]]]
           (mapv
             (fn [[type x]]
               (case type
                 :effect
                 (let [xpd (meta-macroexpand env x)]
                   {:type            :effect
                    :form            (-> x as-form)
                    :macroexpanded   (with-meta (-> xpd as-form)
                                       (meta xpd))
                    :thunk           (with-meta (-> xpd as-thunk)
                                       (meta xpd))
                    :bindings        `(case-bindings)
                    :config-bindings `(case-config-bindings)})

                 :expectation
                 (let [[op m] x]
                   (-> (merge
                         {:type            :expectation
                          :op              op
                          :bindings        `(case-bindings)
                          :config-bindings `(case-config-bindings)
                          :tested
                          (let [xpd  (meta-macroexpand env (-> m :tested))]
                            {:form          (-> m :tested as-form)
                             :macroexpanded (-> xpd as-form)
                             :thunk         (-> xpd as-thunk)})}
                         (when (= op :=)
                           {:expected
                            (let [xpd (meta-macroexpand env (-> m :expected))]
                              {:form          (-> m :expected as-form)
                               :macroexpanded (-> xpd as-form)
                               :thunk         (-> xpd as-wildcard-thunk)})}))
                       ))))))))


  (defmacro tests [& body]
    (when-not (-> (config/config) :elide-tests)
      `(config/with-context {:exec-mode
                             (if (or *running-inner-tests*
                                     (currently-loading?))
                               :on-load :on-eval)}
         (let [c#     (config/config)
               ns#    (current-ns-name)
               block# ~(parse-tests &env body)]
           (if (-> (config/context) :exec-mode (= :on-load))
             (when (or (:store-tests c#)
                       (:run-tests   c#))
               (process-after-load! ns# [block#]))
             (config/with-config {:report    {:level   :case}
                                  :explain   {:level   :case}
                                  :stats     {:enabled false}}
               (when   (:run-tests   c#)
                 (run-execute-report! :block ns# block#)))))
         nil))))

(defn- config-kw? [x]
  (and (keyword? x)
       (not (#{:exclude :all} x)))) ;; kws used for namespace selection

(defn- excludor
  "Works like clojure.core/or but returns false if one of the values
  appears to be :exclude. Not a macro, no control flow."
  [& [a & [b & more] :as all]]
  (cond
    (:exclude (set [a b])) false
    (seq more)            (apply excludor (or a b) more)
    :else                 (or a b)))

(defn test!
  ([]       (let [ns (current-ns-name)]
              (cond
                (currently-loading?) (config/with-config {:run-tests   true
                                                          :store-tests false}
                                       (store-or-run-tests!))
                (get @*tests* ns)    (test! ns)
                :else                (test! :all))))
  ([& args] (let [[conf sels]        (->> (partition-all 2 args)
                                          (split-with (->| first config-kw?)))
                  sels               (ns/parse-selectors (apply concat sels))
                  nss                (filter
                                       (->| (apply juxt sels)
                                            (partial apply excludor))
                                       (ns/find-test-namespaces))
                  ns->tests          (select-keys @*tests* nss)]
              (macros/case :clj (run! require nss))
              (if (empty? ns->tests)
                :no-test
                (config/with-config (into {} (map vec conf))
                  (run-execute-report! :suite ns->tests)
                  nil)))))

;; DONE:
;; - [√] tests should not run twice (when loaded, then when they are run)
;; - [√] display usage
;; - [x] options are passed in a bash style (e.g. --option "value")
;; - [√] or options are passed clojure style (e.g. :option "value")

;; TODO: deftime ?
(macros/case
  :clj
  (defn- print-usage []
    (println "Usage:" "clj -m minitest [config options] [ns selectors]")
    (println "with")
    (newline)
    (println "• config options: A flat config map of edn values that will")
    (println "                  get deep merged into minitest's existing")
    (println "                  config. Symbols will be resolved. Optional.")
    (println "• ns selectors:   One or more ns selectors:")
    (println "                    - a ns name.")
    (println "                    - a glob pattern to match namespaces.")
    (println "                    - the ':all' keyword.")
    (println "                    - ':exclude' followed by a ns selector.")
    (println "                    - a vector of ns selectors.")
    (println "                  Optional. Runs all the tests by default.")
    (println "                  If no selectors other than exclusive")
    (println "                  ones are specified, all the tests will be")
    (println "                  considered.")
    (newline)
    (println "Examples:")
    (println "  clj -m minitest name.space")
    (println "  clj -m minitest [name.space name.space.impl]")
    (println "  clj -m minitest name.*")
    (println "  clj -m minitest \\")
    (println "    {:WHEN {:status {:error {:logo \"🤯\"}}}} \\")
    (println "    hardship.impl")
    (newline)
    (println (source-fn `base-config))
    (newline)
    (let [confile (config/config-file)]
      (if confile
        (do (println "On top of this, here is your config from"
                     (str (->> confile .toURI
                               (.relativize (-> (java.io.File. ".") .toURI))
                               (str "./"))
                          ":"))
            (println (str/trim (slurp confile))))
        (do (println "On top of this, you have no config in either")
            (println "./minitest.edn or ./resources/minitest.edn"))))
    (newline)
    (println "And the resulting config after minitest merges them is:")
    (pprint (config/config))))

(macros/case
  :clj
  (defn -main [& args]
    (if (-> args first #{"help" ":help" "h" "-h" "--help"})
      (print-usage)
      (config/with-context {:env :cli}
        (->> (str \[ (str/join \space args) \])
             edn/read-string
             (apply test!))))))

;; TODO:
;; - [ ] a nice README.
