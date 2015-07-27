(ns lolteambuilder.core
  (:require
    [ring.adapter.jetty :as ring]
    [ring.middleware.params :refer [wrap-params]]
    [compojure.core :refer [defroutes GET POST]]
    [clojure.set :refer [map-invert]]
    [clojure.algo.generic.functor :refer [fmap]]
    [clojure.java.io :as io]
    [clojure.java.jdbc :as sql]
    [clj-postgresql.core :as pg]
    [cheshire.core :as json]
    [stencil.core :as mustache]
    [clj-http.client :as http])
  (:gen-class))

;debugging
(require 'stencil.loader 'clojure.core.cache)
(stencil.loader/set-cache (clojure.core.cache/ttl-cache-factory {} :ttl 0))


(def spec (or (System/getenv "DATABASE_URL")
              "postgresql://localhost:5432/teambuilder"))

(defn create-table [conn]
  (sql/db-do-commands conn
    "DROP TABLE IF EXISTS matches"
    "CREATE TABLE matches (
      matchId integer PRIMARY KEY,
      winners integer[5],
      losers  integer[5]
    )"
    "CREATE INDEX loser_index  ON matches USING GIN (losers)"
    "CREATE INDEX winner_index ON matches USING GIN (winners)"))

(defn seed-data []
    (for [i (range 1 11)
          :let [resname (str "matches" i ".json")
                stream (io/reader (io/resource resname))
                data (json/parse-stream stream)]
          match (get data "matches")]
      match))

(defn team-ids [match]
  (reduce
    #(assoc %1
      (get %2 "teamId")
      (if (get %2 "winner") :winners :losers))
    {} (get match "teams")))

(defn teams [match]
  (let [tid (team-ids match)
        get-tid #(get tid (get % "teamId"))]
    (reduce #(update-in %1
              [(get-tid %2)]
              conj
              (get %2 "championId"))
      {:matchId (get match "matchId")}
      (get match "participants"))))

(defn insert-matches [matches]
  (sql/with-db-transaction [conn spec]
    (doseq [match matches]
      (sql/insert! conn :matches (teams match))
      (print "."))))

(defn get-team [conn winners losers]
  (sql/query conn [
     "SELECT UNNEST(winners) as id, COUNT(*) as count
        FROM matches 
       WHERE ? <@ losers
         AND ? <@ winners
    GROUP BY id
    ORDER BY count DESC
       LIMIT 10" losers winners]))

(def champion-map
  (let [stream (io/reader (io/resource "champion.json"))
        data (get (json/parse-stream stream) "data")]
    (map-invert (fmap #(Integer. (get % "key")) data))))

(defn parse-team [prefix params]
  (or (vals (filter #(and
                       (.startsWith (key %) prefix)
                       (not (empty? (val %))))
                    params))
      ()))

(defn team-page [params]
  (let [winners (parse-team "winner" params)
        losers  (parse-team "loser"  params)
        suggestion (get-team spec winners losers)
        names (map #(get champion-map (:id %)) suggestion)
        champions (map (fn [[id name]] {:name name :id id}) champion-map) ]
    (mustache/render-file "team.html"
      {:params params
       :champions (sort-by :name champions)
       :team (range 5)
       :suggestion (vec names)})))

(defroutes routes
  (GET  "/" [] "Hello world")
  (GET "/team" {params :params} (team-page params)))

(def app (wrap-params routes))

(defn -main []
  (let [port (Integer. (or (System/getenv "PORT") "8080"))]
    (ring/run-jetty app {:port port :join? false})))
