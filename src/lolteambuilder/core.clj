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
    [ring.util.response :refer [redirect]]
    [slingshot.slingshot :refer [try+]]
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
      matchId bigint PRIMARY KEY,
      winners integer[5],
      losers  integer[5]
    )"
    "CREATE INDEX loser_index  ON matches USING GIN (losers)"
    "CREATE INDEX winner_index ON matches USING GIN (winners)"))

(defn seed-data []
    (for [i (range 1 11)
          :let [resname (str "matches" i ".json")
                stream (io/reader (io/resource resname))
                data (json/parse-stream stream true)]
          match (get data :matches)]
      match))

(defn team-ids [match]
  (reduce
    #(assoc %1
      (get %2 :teamId)
      (if (get %2 :winner) :winners :losers))
    {} (get match :teams)))

(defn teams [match]
  (let [tid (team-ids match)
        get-tid #(get tid (get % :teamId))]
    (reduce #(update-in %1
              [(get-tid %2)]
              conj
              (get %2 :championId))
      {:matchId (get match :matchId)}
      (get match :participants))))

(defn insert-matches [conn matches]
  (doseq [match matches]
    (try+
      (sql/insert! conn :matches (teams match))
      (catch java.sql.SQLException e
        (println e)))))

(defn get-team [conn winners losers]
  (sql/query conn [
     "SELECT UNNEST(winners) as id, COUNT(*) as count
        FROM matches 
       WHERE ? <@ losers
         AND ? <@ winners
    GROUP BY id
    ORDER BY count DESC
       LIMIT 10" (or losers ())  (or winners ())]))

(defn filter-matches [conn matches]
  (map :id
       (sql/query conn [
        "SELECT *
           FROM UNNEST(?::bigint[]) AS u(id)
          WHERE NOT EXISTS
                (SELECT 1
                   FROM matches
                  WHERE matches.matchid = u.id)"
         matches])))

(def champion-map
  (let [stream (io/reader (io/resource "champion.json"))
        data (get (json/parse-stream stream) "data")]
    (map-invert (fmap #(Integer. (get % "key")) data))))

(def champion-list (sort-by val champion-map))

(defn parse-team [prefix params]
  (->> (get params prefix)
      (filter seq)
      (map #(Integer. %))))

(defn select-options [champids]
  (for [champid (take 5 (concat champids (repeat -1)))]
    (map (fn [[id name]]
           {:name name :id id :selected (= id champid)})
         champion-list)))

(defn team-page [params]
  (let [winners (parse-team "winner" params)
        losers  (parse-team "loser"  params)
        suggestion (get-team spec winners losers)
        names (map #(get champion-map (:id %)) suggestion)
        teams (map (fn [w l] {:winner w :loser l})
                   (select-options winners) (select-options losers)) ]
    (mustache/render-file "team.html"
      {:params params
       :teams teams
       :suggestion (vec names)})))

(defn api-get [region version api resource params]
  (let [base (str "https://" region ".api.pvp.net/api/lol")
        url (apply str (interpose "/" [base region version api resource]))
        params (assoc params "api_key" (System/getenv "APIKEY"))]
    (loop []
      (println url params)
      (if-let [m (try+
                   (:body (http/get url {:query-params params, :as :json}))
                   (catch [:status 429] {{retry "Retry-After"} :headers}
                     (println "Rate limit exceeded, retry after" retry)
                     (when retry
                       (Thread/sleep (* 1000 (Integer. retry))))))]
        m
        (recur)))))

(defn match-list [region summoner-id]
  (api-get region "v2.2" "matchlist/by-summoner" summoner-id
           {"rankedQueues" "RANKED_SOLO_5x5,RANKED_TEAM_3x3,RANKED_TEAM_5x5"}))

(defn match [region match-id]
  (api-get region "v2.2" "match" match-id {}))

(defn summoner [region summoner-name]
  (api-get region "v1.4" "summoner/by-name" summoner-name {}))

(def regions ["br" "eune" "euw" "kr" "lan" "las" "na" "oce" "ru" "tr"])

(defn normalize [summoner]
  (.replace (.toLowerCase summoner) " " ""))

(defn download-matches [region summoner-id]
  (sql/with-db-connection [conn spec]
    (let [history (match-list region summoner-id)
          match-ids (map :matchId (:matches history))
          match-ids (filter-matches conn match-ids)]
      (insert-matches conn
        (for [id match-ids]
          (match region id))))))

(defn search-page [params]
  (let [region (get params "region")
        summoner-name (normalize (get params "summoner" ""))
        data {:summoner summoner-name
              :region region
              :message "Summoner not found."
              :regions (map (fn [reg] {:name reg :selected (= reg region)}) regions)}
        summoner-data (try+
                        (summoner region summoner-name)
                        (catch [:status 404] _))]
    (future (download-matches region (get-in summoner-data [(keyword summoner-name) :id])))
    (if summoner-data
      (redirect "/team")
      (mustache/render-file "index.html" data))))

(defroutes routes
  (GET  "/" [] (mustache/render-file "index.html"
                 {:regions (map (fn [reg] {:name reg}) regions)}))
  (POST  "/" {params :params} (search-page params))
  (GET  "/riot.txt" [] "c0a9de7b-0355-4dcb-b63a-a7b62c19de83")
  (GET "/team" {params :params} (team-page params)))

(def app (wrap-params routes))

(defn -main []
  (let [port (Integer. (or (System/getenv "PORT") "8080"))]
    (ring/run-jetty app {:port port :join? false})))
