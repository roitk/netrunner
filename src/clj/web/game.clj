(ns web.game
  (:require
   [cheshire.core :as json]
   [cljc.java-time.instant :as inst]
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [cond-plus.core :refer [cond+]]
   [game.core.commands :refer [parse-command]]
   [game.core.diffs :as diffs]
   [game.core.say :refer [make-system-message]]
   [game.core.set-up :refer [init-game]]
   [game.main :as main]
   [jinteki.preconstructed :as preconstructed]
   [jinteki.utils :refer [side-from-str]]
   [medley.core :refer [find-first]]
   [web.app-state :as app-state]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]))

(defn game-diff-json
  "Converts the appropriate diff to json"
  [uid gameid side corp-spectators runner-spectators {:keys [runner-diff corp-diff spect-diff corp-spect-diff runner-spect-diff]}]
  (let [diff (cond
               (= side "Corp")
               corp-diff
               (= side "Runner")
               runner-diff
               (some #(= (:uid %) uid) corp-spectators)
               corp-spect-diff
               (some #(= (:uid %) uid) runner-spectators)
               runner-spect-diff
               :else spect-diff)]
    (json/generate-string {:gameid gameid
                           :diff diff})))

(defn send-state-diffs
  "Sends diffs generated by public-diffs to all connected clients."
  [{:keys [gameid players spectators corp-spectators runner-spectators]} diffs]
  (doseq [{:keys [uid side]} (concat players spectators)
          :when (some? uid)]
    (ws/chsk-send! uid [:game/diff (game-diff-json uid gameid side corp-spectators runner-spectators diffs)])))

(defn update-and-send-diffs!
  "Updates the old-states atom with the new game state, then sends a :game/diff
  message to game clients."
  [f {state :state :as lobby} & args]
  (when (and state @state)
    (let [old-state @state
          _ (apply f state args)
          spectators? (seq (:spectators lobby))
          corp-spectators? (seq (:corp-spectators lobby))
          runner-spectators? (seq (:runner-spectators lobby))
          diffs (diffs/public-diffs old-state state spectators? corp-spectators? runner-spectators?)]
      (swap! state update :history conj (:hist-diff diffs))
      (send-state-diffs lobby diffs))))

(defn handle-message-and-send-diffs!
  "If the given message is a command, passes through to `update-and-send-diffs!`.
  Otherwise, adds the message to the log and only diffs the `:log`."
  [{state :state :as lobby} side user message]
  (when (and state @state)
    (let [message (if (= (str/trim message) "null") " null" message)]
      (if (and side user (parse-command state message))
        (update-and-send-diffs! main/handle-say lobby side user message)
        ;; if side is nil, then it's a notification
        (let [f (if (some? side) main/handle-say main/handle-notification)
              old-state @state
              _ (f state side user message)
              diffs (diffs/message-diffs old-state state)]
          (swap! state update :history conj (:hist-diff diffs))
          (send-state-diffs lobby diffs))))))

(defn select-state [uid {:keys [corp-spectators runner-spectators]} side {:keys [runner-state corp-state spect-state corp-spect-state runner-spect-state]}]
  (json/generate-string
    (case side
      "Corp" corp-state
      "Runner" runner-state
      (cond
        (some #(= (:uid %) uid) corp-spectators)
        corp-spect-state
        (some #(= (:uid %) uid) runner-spectators)
        runner-spect-state
        :else
        spect-state))))

(defn send-state-to-participants
  "Sends full states generated by public-states to all connected clients in lobby."
  [event {:keys [players spectators] :as lobby} diffs]
  (doseq [{:keys [uid side]} (concat players spectators)
          :when (some? uid)]
    (ws/chsk-send! uid [event (select-state uid lobby side diffs)])))

(defn send-state-to-uid!
  "Sends full states generated by public-states to single client in lobby."
  [uid event {:keys [players spectators] :as lobby} diffs]
  (when-let [player (find-first #(= (:uid %) uid) (concat players spectators))]
    (ws/chsk-send! uid [event (select-state uid lobby (:side player) diffs)])))

(defn- is-starter-deck?
  [player]
  (let [id (get-in player [:deck :identity :title])
        card-cnt (reduce + (map :qty (get-in player [:deck :cards])))]
    (or (and (= id "The Syndicate: Profit over Principle")
             (= card-cnt 34))
        (and (= id "The Catalyst: Convention Breaker")
             (= card-cnt 30)))))

(defn- check-for-starter-decks
  "Starter Decks can require 6 or 7 agenda points"
  [game]
  (if (and (= (:format game) "system-gateway")
           (every? is-starter-deck? (:players game)))
    (do
      (swap! (:state game) assoc-in [:runner :agenda-point-req] 6)
      (swap! (:state game) assoc-in [:corp :agenda-point-req] 6)
      game)
    game))

(defn strip-deck [player]
  (-> player
      (update :deck select-keys [:_id :identity :name])
      (update-in [:deck :_id] str)
      (update-in [:deck :identity] select-keys [:title :faction])))

(defn- player-map-deck
  [players uid deck]
  (mapv (fn [p] (if (= uid (:uid p))
                  (assoc p :deck deck)
                  p))
        players))

(defn- side-player-uid
  [game side]
  (let [player (first (filter #(= (:side %) side) (:players game)))]
    (:uid player)))

(defn set-precon-deck
  [game side decklist]
  (if-let [uid (side-player-uid game side)]
    (let [deck (lobby/process-deck decklist)]
      (assoc game :players (player-map-deck (:players game) uid deck)))
    game))

(defn set-precon-match
  [game precon-match]
  (-> game
      (set-precon-deck "Corp" (:corp precon-match))
      (set-precon-deck "Runner" (:runner precon-match))))

(defn handle-precon-decks
  [game]
  (if-let [precon (:precon game)]
    (set-precon-match game (preconstructed/matchup-by-key precon))
    game))

(defn handle-start-game [lobbies gameid players now]
  (if-let [lobby (get lobbies gameid)]
    (as-> lobby g
      (handle-precon-decks g)
      (merge g {:started true
                :original-players players
                :ending-players players
                :start-date now
                :last-update now
                :state (init-game g)})
      (check-for-starter-decks g)
      (update g :players #(mapv strip-deck %))
      (assoc lobbies gameid g))
    lobbies))

(defmethod ws/-msg-handler :game/start
  game--start
  [{{db :system/db} :ring-req
    uid :uid
    {gameid :gameid} :?data}]
  (lobby/lobby-thread
    (let [{:keys [players started] :as lobby} (app-state/get-lobby gameid)]
      (when (and lobby (lobby/first-player? uid lobby) (not started))
        (let [now (inst/now)
              new-app-state
              (swap! app-state/app-state
                     update :lobbies handle-start-game gameid players now)
              lobby? (get-in new-app-state [:lobbies gameid])]
          (when lobby?
            (stats/game-started db lobby?)
            (lobby/send-lobby-state lobby?)
            (lobby/broadcast-lobby-list)
            (send-state-to-participants :game/start lobby? (diffs/public-states (:state lobby?)))))))))

(defmethod ws/-msg-handler :game/leave
  game--leave
  [{{db :system/db user :user} :ring-req
    uid :uid
    {gameid :gameid} :?data
    ?reply-fn :?reply-fn}]
  (lobby/lobby-thread
    (let [{:keys [started state] :as lobby} (app-state/get-lobby gameid)]
      (when (and lobby (lobby/in-lobby? uid lobby) started state)
        ;; The game will not exist if this is the last player to leave.
        (when-let [lobby? (lobby/leave-lobby! db user uid nil lobby)]
          (handle-message-and-send-diffs!
            lobby? nil nil (str (:username user) " has left the game.")))
        (lobby/send-lobby-list uid)
        (lobby/broadcast-lobby-list)
        (when ?reply-fn (?reply-fn true))))))

(defn uid-in-lobby-as-original-player? [uid]
  (find-first
    (fn [lobby]
      (some #(= uid (:uid %)) (:original-players lobby)))
    (vals (:lobbies @app-state/app-state))))

(defmethod ws/-msg-handler :game/rejoin
  game--rejoin
  [{{user :user} :ring-req
    uid :uid
    ?data :?data}]
  (lobby/lobby-thread
    (let [{:keys [original-players started players] :as lobby} (uid-in-lobby-as-original-player? uid)
          original-player (find-first #(= uid (:uid %)) original-players)]
      (when (and started
                 original-player
                 (< (count (remove #(= uid (:uid %)) players)) 2))
        (let [?data (assoc ?data :request-side "Any Side")
              lobby? (lobby/join-lobby! user uid ?data nil lobby)]
          (when lobby?
            (send-state-to-uid! uid :game/start lobby? (diffs/public-states (:state lobby?)))
            (update-and-send-diffs! main/handle-rejoin lobby? user)))))))

(defmethod ws/-msg-handler :game/concede
  game--concede
  [{uid :uid
    {gameid :gameid} :?data}]
  (let [lobby (app-state/get-lobby gameid)
        player (lobby/player? uid lobby)]
    (lobby/game-thread
      lobby
      (when (and lobby player)
        (let [side (side-from-str (:side player))]
          (update-and-send-diffs! main/handle-concede lobby side))))))

(defmethod ws/-msg-handler :game/action
  game--action
  [{uid :uid
    {:keys [gameid command args]} :?data}]
  (try
    (let [{:keys [state] :as lobby} (app-state/get-lobby gameid)
          player (lobby/player? uid lobby)
          spectator (lobby/spectator? uid lobby)]
      (lobby/game-thread
        lobby
        (cond
          (and state player)
          (let [old-state @state
                side (side-from-str (:side player))]
            (try
              (swap! app-state/app-state
                     update :lobbies lobby/handle-set-last-update gameid uid)
              (update-and-send-diffs! main/handle-action lobby side command args)
              (catch Exception e
                (reset! state old-state)
                (throw e))))
          (and (not spectator) (not= command "toast"))
          (throw (ex-info "handle-game-action unknown state or side"
                          {:gameid gameid
                           :uid uid
                           :players (map #(select-keys % [:uid :side]) (:players lobby))
                           :spectators (map #(select-keys % [:uid]) (:spectators lobby))
                           :command command
                           :args args})))))
    (catch Exception e
      (ws/chsk-send! uid [:game/error])
      (println (str "Caught exception"
                    "\nException Data: " (or (ex-data e) (.getMessage e))
                    "\nStacktrace: " (with-out-str (stacktrace/print-stack-trace e 100)))))))

(defmethod ws/-msg-handler :game/resync
  game--resync
  [{uid :uid
    {gameid :gameid} :?data}]
  (let [lobby (app-state/get-lobby gameid)]
    (lobby/game-thread
      lobby
      (when (and lobby (lobby/in-lobby? uid lobby))
        (if-let [state (:state lobby)]
          (send-state-to-uid! uid :game/resync lobby (diffs/public-states state))
          (println (str "resync request unknown state"
                        "\nGameID:" gameid
                        "\nGameID by ClientID:" gameid
                        "\nClientID:" uid
                        "\nPlayers:" (map #(select-keys % [:uid :side]) (:players lobby))
                        "\nSpectators" (map #(select-keys % [:uid]) (:spectators lobby)))))))))

(defmethod ws/-msg-handler :game/watch
  game--watch
  [{{user :user} :ring-req
    uid :uid
    {:keys [gameid password request-side]} :?data
    ?reply-fn :?reply-fn}]
  (lobby/lobby-thread
    (let [lobby (app-state/get-lobby gameid)]
      (when (and lobby (lobby/allowed-in-lobby user lobby))
        (let [correct-password? (lobby/check-password lobby user password)
              watch-str (str (:username user) " joined the game as a spectator" (when request-side (str " (" request-side " perspective)")) ".")
              watch-message (make-system-message watch-str)
              new-app-state (swap! app-state/app-state
                                   update :lobbies
                                   #(-> %
                                        (lobby/handle-watch-lobby gameid uid user correct-password? watch-message request-side)
                                        (lobby/handle-set-last-update gameid uid)))
              lobby? (get-in new-app-state [:lobbies gameid])]
          (cond
            (and lobby? (lobby/spectator? uid lobby?) (lobby/allowed-in-lobby user lobby?))
            (do
              (lobby/send-lobby-state lobby?)
              (lobby/send-lobby-ting lobby?)
              (lobby/broadcast-lobby-list)
              (main/handle-notification (:state lobby?) watch-str)
              (send-state-to-uid! uid :game/start lobby? (diffs/public-states (:state lobby?)))
              (when ?reply-fn (?reply-fn 200)))
            (false? correct-password?)
            (when ?reply-fn (?reply-fn 403))
            :else
            (when ?reply-fn (?reply-fn 404))))))))

(defmethod ws/-msg-handler :game/mute-spectators
  game--mute-spectators
  [{{user :user} :ring-req
    uid :uid
    {gameid :gameid} :?data}]
  (let [new-app-state (swap! app-state/app-state update :lobbies #(-> %
                                                                      (lobby/handle-toggle-spectator-mute gameid uid)
                                                                      (lobby/handle-set-last-update gameid uid)))
        {:keys [state mute-spectators] :as lobby?} (get-in new-app-state [:lobbies gameid])
        message (if mute-spectators "muted" "unmuted")]
    (when (and lobby? state (lobby/player? uid lobby?))
      (lobby/game-thread
        lobby?
        (handle-message-and-send-diffs! lobby? nil nil (str (:username user) " " message " spectators."))
        ;; needed to update the status bar
        (lobby/send-lobby-state lobby?)))))

(defmethod ws/-msg-handler :game/say
  game--say
  [{{user :user} :ring-req
    uid :uid
    {:keys [gameid msg]} :?data}]
  (let [new-app-state (swap! app-state/app-state update :lobbies lobby/handle-set-last-update gameid uid)
        {:keys [state mute-spectators] :as lobby?} (get-in new-app-state [:lobbies gameid])
        side (cond+
               [(lobby/player? uid lobby?) :> #(side-from-str (:side %))]
               [(and (not mute-spectators) (lobby/spectator? uid lobby?)) :spectator])]
    (when (and lobby? state side)
      (lobby/game-thread
        lobby?
        (handle-message-and-send-diffs! lobby? side user msg)))))

(defmethod ws/-msg-handler :game/typing
  game--typing
  [{uid :uid
    {:keys [gameid typing]} :?data}]
  (let [{:keys [state players] :as lobby} (app-state/get-lobby gameid)]
    (lobby/game-thread
      lobby
      (when (and state (lobby/player? uid lobby))
        (doseq [{:keys [uid]} (remove #(= uid (:uid %)) players)]
          (ws/chsk-send! uid [:game/typing typing]))))))

(defmethod ws/-msg-handler :chsk/uidport-close
  chsk--uidport-close
  [{{db :system/db
     user :user} :ring-req
    uid :uid
    ?reply-fn :?reply-fn}]
  (lobby/lobby-thread
    (let [{:keys [started state] :as lobby} (app-state/uid->lobby uid)]
      (when (and started state)
        ;; The game will not exist if this is the last player to leave.
        (when-let [lobby? (lobby/leave-lobby! db user uid nil lobby)]
          (handle-message-and-send-diffs!
            lobby? nil nil (str (:username user) " has left the game.")))))
    (lobby/send-lobby-list uid)
    (lobby/broadcast-lobby-list)
    (app-state/deregister-user! uid)
    (when ?reply-fn (?reply-fn true))))
