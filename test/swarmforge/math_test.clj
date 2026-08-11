(ns swarmforge.math-test
  (:require [speclj.core :refer :all]
            [swarmforge.math :refer :all]))

(describe "swarmforge.math"
  (it "adds two numbers"
    (should= 5 (add 2 3))))

(run-specs)
