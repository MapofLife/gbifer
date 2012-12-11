(ns gulo.core
  "Cascalog queries for transforming CSV Darwin Core data into CartoDB tables."
  (:use [gulo.util :as util :only (latlon-valid? gen-uuid)]
        [cascalog.api]
        [cascalog.more-taps :as taps :only (hfs-delimited)]
        [dwca.core :as dwca])
  (:require [cascalog.ops :as c])
  (:import [org.gbif.dwc.record DarwinCoreRecord]))

;; Minimum number of values per species - anything *less than or equal*
;; to this value will be handled by Fossa
(def MIN-OBS 40000)

;; Position of values in a split texline.
(def SCINAME 0)
(def OCC-ID 1)
(def LAT 2)
(def LON 3)

;; Fields
(def FIELDS
  ["?sci-name" "?occ-id" "?lat" "?lon" "?precision" "?year" "?month" "?season"])

(defn- my-filter [& vals] (println (str "VAL--------------" vals)) true)

(defn makeline
  "Returns a string line by joining a sequence of values on tab."
  [& vals]
  (clojure.string/join \tab vals))

(defn splitline
  "Returns vector of line values by splitting on tab."
  [line]
  (vec (.split line "\t")))

(defn line->loc
  "Return 3-tuple [occid lat lon] from supplied textline."
  [line]
  (let [vals (splitline line)]
    (map (partial nth vals) [OCC-ID LAT LON])))

(defn line->name
  "Return 2-tuple [occid scientificname] from supplied textline."
  [line]
  (let [vals (splitline line)]
    (map (partial nth vals) [OCC-ID SCINAME])))

(defn line->locname
  "Return 4-tuple [occid lat lon name] from supplied textline."
  [line]
  (let [[occid lat lon] (line->loc line)
        [_ name] (line->name line)]
    [occid lat lon name]))

(defn occ-table
  "Build occ and tax_loc tables."
  [occ-src tax-src loc-src tax-loc-src occ-sink]
  (let [result-vector (vec (cons "?tax-loc-id" FIELDS))
        tax-loc-occ-src (<- [?taxon-id ?loc-id ?occ-id ?sci-name ?lat ?lon]
                            (tax-src ?tax-line)
                            (splitline ?tax-line :> ?taxon-id ?sci-name)
                            (loc-src ?loc-line)
                            (splitline ?loc-line :> ?loc-id ?lat ?lon _)
                            (occ-src :>> FIELDS))]
    (?<- occ-sink
         result-vector
         (tax-loc-occ-src ?taxon-id ?loc-id ?occ-id ?sci-name ?lat ?lon)
         (tax-loc-src ?tax-loc-line)
         (splitline ?tax-loc-line :> ?tax-loc-id ?taxon-id ?loc-id)
         (occ-src :>> FIELDS))))

(defn tax-loc-table
  "Build tax_loc table."
  [occ-src tax-src loc-src tax-loc-sink]
  (let [tax-loc-occ-src (<- [?taxon-id ?loc-id]
                            (tax-src ?tax-line)
                            (splitline ?tax-line :> ?taxon-id ?sci-name)
                            (loc-src ?loc-line)
                            (splitline ?loc-line :> ?loc-id ?lat ?lon _)
                            (occ-src :>> FIELDS)
                            (:distinct true))]
    (?<- tax-loc-sink
         [?line]
         (tax-loc-occ-src ?taxon-id ?loc-id)
         (util/gen-uuid :> ?tax-loc-id)
         (makeline ?tax-loc-id ?taxon-id ?loc-id :> ?line))))

(deffilterop valid-name?
  "Return true if name is valid, otherwise return false."
  [name]
  (and (not= name nil) (not= name "")))

(defn taxon-table
  "Create taxon table with unique [uuid name] from supplied source of Darwin Core
  textlines."
  [source sink]
  (let [uniques (<- [?sci-name]
                    (source :>> FIELDS)
                    (valid-name? ?sci-name)
                    (:distinct true))]
    (?<- sink
         [?line]
         (uniques ?sci-name)
         (util/gen-uuid :> ?uuid)
         (makeline ?uuid ?sci-name :> ?line))))

(defn location-table
  "Create location table with unique lines [uuid lat lon wkt] from supplied
  source of Darwin Core textlines."
  [source sink]
  (let [uniques (<- [?lat ?lon]
                    (source :>> FIELDS)
                    (util/latlon-valid? ?lat ?lon)
                    (:distinct true))]
    (?<- sink
         [?line]
         (uniques ?lat ?lon)
         (util/wkt-point ?lat ?lon :> ?wkt)
         (util/gen-uuid :> ?uuid)
         (makeline ?uuid ?lat ?lon ?wkt :> ?line)
         (:distinct true))))

(defn obs-with-min
  "Return list of scientific names with more observations than MIN-OBS"
  [src]
  (<- [?name]
      (src ?name _ _ _ _ _ _ _)
      (c/count ?count)
      (< MIN-OBS ?count)
      (:distinct true)))

(defn filter-infrequent
  "Filter out records for scientific names
   that have fewer observations than MIN-OBS"
  [occ-src]
  (let [to-keep (set (flatten (??- (obs-with-min occ-src))))]
    (<- [?name ?occids ?lats ?lons ?precisions ?years ?months ?season]
        (occ-src ?name ?occids ?lats ?lons ?precisions ?years ?months ?season)
        (contains? to-keep ?name))))
