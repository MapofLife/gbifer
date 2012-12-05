(ns gulo.main-test
  "Integration testing"
  (:use gulo.main
        [gulo.core :only (MIN-OBS)]
        cascalog.api
        [midje sweet]))

(def TMP "/tmp/occ")

(def sample-occ-data
  (into [["Passer domesticus" "999999999" "-40.8747" "170.851" "" "2007" "6"]]
        (repeat (inc MIN-OBS) ["Really big ants" "222222222" "-40.8747283" "170.851" "10" "2007" ""])))

(defn sink-fake-data
  []
  (?- (hfs-seqfile TMP :sinkmode :replace) sample-occ-data))

(fact
  "Check that Shred runs through"
  (sink-fake-data)
  (Shred TMP "/tmp/output")
  (let [pattern #"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"]
    (clojure.string/replace (first (ffirst (??- (hfs-textline "/tmp/output/occ"))))
                            pattern ""))
  => "\t\t222222222\tReally big ants\t-40.8747283\t170.851")

