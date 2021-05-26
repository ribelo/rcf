# RCF – turn your Rich Comment Forms into tests

RCF is a REPL-friendly Clojure/Script test macro and notation for describing what code does, or should do. We find it especially good for brainstorming. A well-formed idea, presented for consideration, comes in the form of an RCF.

# Usage

```clojure
(ns example
  (:require [hyperfiddle.rcf :refer [tests]]))

(tests
  "equality"
  (inc 1) := 2

  "wildcards"
  {:a :b, :b [2 :b]} := {:a _, _ [2 _]}

  "unification"
  {:a :b, :b [2 :b]} := {:a ?b, ?b [2 ?b]}

  "unification on reference types"
  (def x (atom nil))
  {:a x, :b x} := {:a ?x, :b ?x}

  "the usual REPL bindings"
  :foo
  :bar
  :baz
  *3 := :foo
  *2 := :bar
  *1 := :baz

  (tests
    "nested tests for convenience"
    1 := 1))
```

Tests are run when you send a file or form to your Clojure/Script REPL. In Cursive, that's cmd-shift-L to re-run the file.

```text
Loading src/example.cljc... 
✅✅✅✅✅✅✅✅Loaded
```

# Configuration

`(tests)` blocks erase by default (macroexpanding to nothing). They will only run and assert under a flag:

```Clojure
; deps.edn
{:aliases {:dev  {:jvm-opts ["-Dhyperfiddle.rcf.enabled=true"]}
           :test {:jvm-opts ["-Dhyperfiddle.rcf.generate-tests=true"]}}}
```

Unfortunately, this will run all your tests at startup when the namespaces load, which is too slow. To prevent this, wrap your dev entrypoint like below. Subsequent REPL interactions will still run tests.

```Clojure
; dev entrypoint
(ns dev (:require hyperfiddle.rcf))
(binding [hyperfiddle.rcf/*enabled* false]
  (require 'example)) ; erase tests
; Subsequent REPL interactions will still run tests. 
; Subsequent `(require ...)` will also run tests, which is rather nice.
```

In ClojureScript, your build tool might load namespaces and thus run tests when you save the corresponding file.
To prevent it:

```Clojure
(ns js-runtime-example
  (:require [hyperfiddle.rcf :refer-macros [tests]]))

(tests 1 := 1)

(defn ^:dev/before-load stop [] (set! hyperfiddle.rcf/*enabled* false))
(defn ^:dev/after-load start [] (set! hyperfiddle.rcf/*enabled* true))
```

Tests are always erased in cljs `:advanced` compilation mode.

We explored fixing the startup problem with monkeypatches to clojure.core/load and the ClojureScript module loader. We determined the monkeypatch approach to be workable, but not worth deploying yet as the flag is good enough for now.

The :test alias will generate clojure.test deftest vars for use in CI:

```bash
% clj -M:test -e "(require 'example)(clojure.test/run-tests 'example)"

Testing example
✅✅✅✅✅✅✅✅
Ran 1 tests containing 8 assertions.
0 failures, 0 errors.
{:test 1, :pass 8, :fail 0, :error 0, :type :summary}
```

# FAQ

*One of my tests threw an exception, but the stack trace is empty?* — you want `{:jvm-opts ["-XX:-OmitStackTraceInFastThrow"]}` [explanation](https://web.archive.org/web/20190416091616/http://yellerapp.com/posts/2015-05-11-clojure-no-stacktrace.html) (this may be JVM specific)

# Contributing

Sure – you can reach us on clojurians.net in #hyperfiddle or ping @dustingetz.

# Acknowledgments

Thank you to https://github.com/tristefigure for discovery, first implementations, and especially the work on the ClojureScript compiler monkey patches. RCF was not easy to write.

![Scroll Of Truth meme saying "you do not really understand something until you can explain it as a passing test".](./doc/meme.png)
