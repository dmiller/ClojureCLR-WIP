(ns clojure.tools.analyzer.passes-test
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer.ast :refer :all]
            [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [clojure.tools.analyzer.core-test :refer [ast e e1]]
            [clojure.tools.analyzer.passes.add-binding-atom :refer [add-binding-atom]]
            [clojure.tools.analyzer.passes.source-info :refer [source-info]]
            [clojure.tools.analyzer.passes.uniquify :refer [uniquify-locals]]
            [clojure.tools.analyzer.passes.constant-lifter :refer [constant-lift]]
            [clojure.tools.analyzer.passes.emit-form :refer [emit-form emit-hygienic-form]]
            [clojure.tools.analyzer.env :refer [with-env]]))

(deftest passes-utils-test
  (let [ast {:foo [{:a 1} {:a 2}] :bar [{:a 3}] :children [:foo :bar]}]
    (is (= 2 (-> ast (prewalk (fn [ast] (if (:a ast)
                                        (update-in ast [:a] inc)
                                        ast)))
                :foo first :a)))
    (is (= 2 (-> ast (postwalk (fn [ast] (if (:a ast)
                                         (update-in ast [:a] inc)
                                         ast)))
                :foo first :a)))
    (is (= nil (-> ast (walk (fn [ast] (dissoc ast :a))
                          (fn [ast] (if (:a ast)
                                     (update-in ast [:a] inc)
                                     ast)))
                :foo first :a)))
    (is (= [3 2 1] (let [a (atom [])]
                     (-> ast (postwalk
                             (fn [ast] (when-let [el (:a ast)]
                                        (swap! a conj el))
                               ast) :reversed))
                     @a)))
    (is (= [[{:a 1} {:a 2}] [{:a 3}]] (mapv second (children* ast))))
    (is (= [{:a 1} {:a 2} {:a 3}] (children ast)))))

(deftest add-binding-atom-test
  (let [the-ast (prewalk (ast (let [a 1] a))
                         (partial add-binding-atom (atom {})))]
    (swap! (-> the-ast :bindings first :atom) assoc :a 1)
    (is (= 1 (-> the-ast :body :ret :atom deref :a)))))

(deftest source-info-test
  (is (= 1 (-> {:form ^{:line 1} [1]} source-info :env :line)))
  (is (= 1 (-> {:form ^{:column 1 :line 1} [1]} source-info :env :column)))
  (is (= 1 (-> {:form ^{:end-line 1 :line 1} [1]} source-info :env :end-line)))
  (is (= 1 (-> {:form ^{:end-column 1 :line 1} [1]} source-info :env :end-column))))

(deftest constant-lift-test
  (is (= :const (-> (ast {:a {:b :c}}) (postwalk constant-lift) :op)))
  (is (not= :const (-> (ast {:a {:b #()}}) (postwalk constant-lift) :op)))
  (is (= :const (-> (ast [:foo 1 "bar" #{#"baz" {23 []}}])
                  (postwalk constant-lift) :op))))

(deftest uniquify-test
  (let [the-ast (with-env e1
                  (uniquify-locals (ast (let [x 1 y x x (let [x x] x)]
                                          (fn [y] x)))))]
    (is (= 'x__#2 (-> the-ast :body :ret :ret :methods first :body :ret :name)))
    (is (= 'y__#1 (-> the-ast :body :ret :ret :methods first :params first :name)))
    (is (apply not= (->> the-ast :bindings (mapv :name))))))

(deftest emit-form-test
  (is (= 1 (emit-form (ast 1))))
  (is (= "a" (emit-form (ast "a"))))
  (is (= :foo/bar (emit-form (ast :foo/bar))))
  (is (= 'a (emit-form (ast a))))
  (is (= 'a/b (emit-form (ast a/b))))
  (is (= 'a.b (emit-form (ast a.b))))
  (is (= 'a.b/c (emit-form (ast a.b/c))))
  (is (= '(. b (a c)) (emit-form (ast (.a b c)))))
  (is (= '(. b (a (c))) (emit-form (ast (.a b (c))))))
  (is (= '(. b -a) (emit-form (ast (.-a b)))))
  (is (= '(. b a) (emit-form (ast (.a b)))))
  (is (= '(let* [a 1] a) (emit-form (ast (let [a 1] a)))))
  (is (= '(fn* ([] nil)) (emit-form (ast (fn [])))))
  (is (= '(fn* ([] nil) ([a] nil)) (emit-form (ast (fn ([]) ([a]))))))
  (is (= '(loop* [a 1] (recur 2)) (emit-form (ast (loop [a 1] (recur 2))))))
  (is (= ''a (emit-form (ast 'a))))
  (is (= [1 2 3] (emit-form (ast [1 2 3]))))
  (is (= {:a 1 [:b] 2} (emit-form (ast {:a 1 [:b] 2}))))
  (is (= {:a 1} (meta (emit-form (ast ^{:a 1} [:foo])))))
  (is (= '(do 1) (emit-form (ast (do 1)))))
  (is (= '(do a b c) (emit-form (ast (do a b c)))))
  (is (= '(if 1 2) (emit-form (ast (if 1 2)))))
  (is (= '(if 1 2 3) (emit-form (ast (if 1 2 3)))))
  (is (= '(new a b c) (emit-form (ast (a. b c)))))
  (is (= '(set! a 1) (emit-form (ast (set! a 1)))))
  (is (= '(def a 1) (emit-form (ast (def a 1)))))
  (is (= '(def a "doc" 1) (emit-form (ast (def a "doc" 1)))))
  (is (= '(a b) (emit-form (ast (a b)))))
  (is (= '(try (throw 1) (catch e t b) (finally 2))
         (emit-form (ast (try (throw 1) (catch e t b) (finally 2)))))))

(deftest emit-hygienic-form-test
  (with-env e1
    (is (= '(let* [a__#0 1 a__#1 a__#0] a__#1)
           (emit-hygienic-form (uniquify-locals (ast (let [a 1 a a] a))))))
    (is (= '(let* [x__#0 1] (fn* ([x__#1] x__#1)))
           (emit-hygienic-form (uniquify-locals (ast (let [x 1] (fn [x] x)))))))
    (is (= '(fn* x__#0 ([x__#1] x__#1))
           (emit-hygienic-form (uniquify-locals (ast (fn x [x] x))))))))

(deftest deeply-nested-uniquify
  (is (= '(fn* ([x__#0 y__#0 z__#0]
                  (let* [foo__#0 (fn* ([y__#1 z__#1] [y__#1 z__#1]))]
                        (foo__#0 x__#0 y__#0))))
         (with-env e1
          (emit-hygienic-form (uniquify-locals (ast (fn [x y z]
                                                      (let [foo (fn [y z]
                                                                  [y z])]
                                                        (foo x y))))))))))