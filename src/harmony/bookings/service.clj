(ns harmony.bookings.service
  (:require [clj-time.core :as t]
            [clj-time.periodic :as periodic]
            [clj-time.coerce :as coerce]
            [harmony.util.time :as time]
            [harmony.bookings.db :as db]))

(defn- bookable-defaults [m-id ref-id author-id]
  (let [plan {:marketplaceId m-id
              :seats 1
              :planMode :available}
        bookable {:marketplaceId m-id
                  :refId ref-id
                  :authorId author-id
                  :unitType :day
                  :activePlan plan}]
    {:bookable bookable
     :plan plan}))

(defn create-bookable
  [db create-cmd]
  (let [{:keys [marketplaceId refId authorId]} create-cmd]
    (when-not (:id (db/fetch-bookable
                    db
                    {:marketplaceId marketplaceId :refId refId}
                    {:cols :id}))
      (let [{:keys [bookable plan]}
            (bookable-defaults marketplaceId refId authorId)
            _ (db/create-bookable db bookable plan)
            {:keys [bookable active-plan]}
            (db/fetch-bookable-with-plan db {:marketplaceId marketplaceId :refId refId})]
        (assoc bookable :activePlan active-plan)))))

(defn fetch-bookable
  [db m-id ref-id]
  (when-let [{:keys [bookable active-plan]}
             (db/fetch-bookable-with-plan
              db
              {:marketplaceId m-id :refId ref-id})]
    (assoc bookable :activePlan active-plan)))

(defn- active-bookings
  "Takes bookings and returns active bookings, i.e. bookings that will
  reserve a timeslot"
  [bookings]
  (remove #(= (:status %) :rejected) bookings))

(defn- free-dates [start end bookings]
  (let [booking-is (map #(t/interval (time/midnight-date-time (:start %))
                                     (time/midnight-date-time (:end %)))
                        (active-bookings bookings))
        booked? (fn [dt]
                  (let [day-i (t/interval dt (t/plus dt (t/days 1)))]
                    (some #(t/overlaps? day-i %) booking-is)))]
    (->> (periodic/periodic-seq
          (time/midnight-date-time start)
          (time/midnight-date-time end)
          (t/days 1))
         (remove booked?))))

(defn- time-slot [ref-id date-time]
  {:id (java.util.UUID/randomUUID)
   :refId ref-id
   :unitType :day
   :seats 1
   :start (coerce/to-date date-time)
   :end (-> date-time
            (t/plus (t/days 1))
            (coerce/to-date))
   :year (t/year date-time)
   :month (t/month date-time)
   :day (t/day date-time)})


(defn calc-free-time-slots
  [db {:keys [marketplaceId refId start end]}]
  (let [{bookable-id :id} (db/fetch-bookable
                                db
                                {:marketplaceId marketplaceId :refId refId}
                                {:cols :id})]
    (when bookable-id
      (let [bookings (db/fetch-bookings db {:bookableId bookable-id
                                                     :start start
                                                     :end end})]
        (->> (free-dates start end bookings)
             (map #(time-slot refId %)))))))

(defn- booking-defaults [booking-cmd bookable-id]
  (let [{:keys [:marketplaceId :customerId :start :end :initialStatus]}
        booking-cmd]
    {:marketplaceId marketplaceId
     :customerId customerId
     :start (time/midnight-date start)
     :end (time/midnight-date end)
     :bookableId bookable-id
     :seats 1
     :status initialStatus}))

(defn initiate-booking [db cmd]
  (let [{:keys [marketplaceId refId]} cmd]
    (let [{bookable-id :id} (db/fetch-bookable
                             db
                             {:marketplaceId marketplaceId :refId refId}
                             {:cols :id})]
      (when bookable-id
        (let [booking (booking-defaults cmd bookable-id)
              booking-id (db/create-booking db booking)]
          (if booking-id
            (db/fetch-booking db {:id booking-id})
            nil))))))

(defn accept-booking [db id]
  (let [{booking-id :id} (db/fetch-booking db {:id id}, {:cols :id})]
    (when booking-id
      (db/modify-booking-status db {:id booking-id :status :accepted})
      (db/fetch-booking db {:id booking-id}))))

(defn reject-booking [db id]
  (let [{booking-id :id} (db/fetch-booking db {:id id}, {:cols :id})]
    (when booking-id
      (db/modify-booking-status db {:id booking-id :status :rejected})
      (db/fetch-booking db {:id booking-id}))))

(comment
  (def db (store/new-mem-booking-store))
  (create-bookable db {:marketplaceId 1234 :refId 4444 :authorId 27272})

  (t/day (t/date-midnight 2016 8 31))
  (free-dates #inst "2016-09-09T10:02:01"
              #inst "2016-09-15")
  )
