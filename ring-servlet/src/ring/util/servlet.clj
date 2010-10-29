(ns ring.util.servlet
  "Compatibility functions for turning a ring handler into a Java servlet."
  (:use [clojure.contrib.except :only (throwf)]
        [clojure.contrib.duck-streams :only (copy)])
  (:import (java.io File InputStream FileInputStream)
           (javax.servlet.http HttpServlet
                               HttpServletRequest
                               HttpServletResponse)))

(defn- get-headers
  "Creates a name/value map of all the request headers."
  [^HttpServletRequest request]
  (reduce
    (fn [headers, ^String name]
      (assoc headers
        (.toLowerCase name)
        (.getHeader request name)))
    {}
    (enumeration-seq (.getHeaderNames request))))

(defn- get-content-length
  "Returns the content length, or nil if there is no content."
  [^HttpServletRequest request]
  (let [length (.getContentLength request)]
    (if (>= length 0) length)))

(defn build-request-map
  "Create the request map from the HttpServletRequest object."
  [^HttpServletRequest request]
  {:server-port        (.getServerPort request)
   :server-name        (.getServerName request)
   :remote-addr        (.getRemoteAddr request)
   :uri                (.getRequestURI request)
   :query-string       (.getQueryString request)
   :scheme             (keyword (.getScheme request))
   :request-method     (keyword (.toLowerCase (.getMethod request)))
   :headers            (get-headers request)
   :content-type       (.getContentType request)
   :content-length     (get-content-length request)
   :character-encoding (.getCharacterEncoding request)
   :body               (.getInputStream request)})

(defn merge-servlet-keys
  "Associate servlet-specific keys with the request map for use with legacy
  systems."
  [request-map
   ^HttpServlet servlet
   ^HttpServletRequest request
   ^HttpServletResponse response]
  (merge request-map
    {:servlet          servlet
     :servlet-request  request
     :servlet-response response
     :servlet-context  (.getServletContext servlet)}))

; depending on type of header we're trying to set on the response, choose different setter functions
(defmulti  ^{:private true} set-header #(class %3))
(defmethod set-header Number
	[^HttpServletResponse response  ^String name  ^Number val]
	(.setIntHeader response name (int val)))
(defmethod set-header java.util.Date
	[^HttpServletResponse response  ^String name  ^java.util.Date val]
	(.setDateHeader response name (.getTime val)))
(defmethod set-header java.util.Calendar
	[^HttpServletResponse response  ^String name  ^java.util.Calendar val]
	(.setDateHeader response name (.getTimeInMillis val)))
(defmethod set-header String
	[^HttpServletResponse response  ^String name  ^String val]
	(.setHeader response name val))

(defmulti  ^{:private true} add-header #(class %3))
(defmethod add-header Number
	[^HttpServletResponse response  ^String name  ^Number val]
	(.addIntHeader response name (int val)))
(defmethod add-header java.util.Date
	[^HttpServletResponse response  ^String name  ^java.util.Date val]
	(.addDateHeader response name (.getTime val)))
(defmethod add-header java.util.Calendar
	[^HttpServletResponse response  ^String name  ^java.util.Calendar val]
	(.addDateHeader response name (.getTimeInMillis val)))
(defmethod add-header String
	[^HttpServletResponse response  ^String name  ^String val]
	(.addHeader response name val))

(defn- set-headers
  "Update a HttpServletResponse with a map of headers."
  [^HttpServletResponse response, headers]
  (doseq [[key val-or-vals] headers]
    (if (seq? val-or-vals)
      (doseq [val val-or-vals]
        (add-header response key val))
      (set-header response key val-or-vals)))
  ; Some headers must be set through specific methods
  (when-let [content-type (get headers "Content-Type")]
    (.setContentType response content-type)))

(defn- set-body
  "Update a HttpServletResponse body with a String, ISeq, File or InputStream."
  [^HttpServletResponse response, body]
  (cond
    (string? body)
      (with-open [writer (.getWriter response)]
        (.println writer body))
    (seq? body)
      (with-open [writer (.getWriter response)]
        (doseq [chunk body]
          (.print writer (str chunk))
          (.flush writer)))
    (instance? InputStream body)
    (let [^InputStream b body]
      (with-open [out (.getOutputStream response)]
        (copy b out)
        (.close b)
        (.flush out)))
    (instance? File body)
    (let [^File f body]
      (with-open [stream (FileInputStream. f)]
        (set-body response stream)))
    (nil? body)
      nil
    :else
      (throwf "Unrecognized body: %s" body)))

(defn update-servlet-response
  "Update the HttpServletResponse using a response map."
  [^HttpServletResponse response, {:keys [status headers body]}]
  (when-not response
    (throw (Exception. "Null response given.")))
  (when status
    (.setStatus response status))
  (doto response
    (set-headers headers)
    (set-body body)))

(defn make-service-method
  "Turns a handler into a function that takes the same arguments and has the
  same return value as the service method in the HttpServlet class."
  [handler]
  (fn [^HttpServlet servlet
       ^HttpServletRequest request
       ^HttpServletResponse response]
    (.setCharacterEncoding response "UTF-8")
    (let [request-map (-> request
                        (build-request-map)
                        (merge-servlet-keys servlet request response))]
      (if-let [response-map (handler request-map)]
        (update-servlet-response response response-map)
        (throw (NullPointerException. "Handler returned nil"))))))

(defn servlet
  "Create a servlet from a Ring handler.."
  [handler]
  (proxy [HttpServlet] []
    (service [request response]
      ((make-service-method handler)
         this request response))))

(defmacro defservice
  "Defines a service method with an optional prefix suitable for being used by
  genclass to compile a HttpServlet class.
  e.g. (defservice my-handler)
       (defservice \"my-prefix-\" my-handler)"
  ([handler]
   `(defservice "-" ~handler))
  ([prefix handler]
   `(defn ~(symbol (str prefix "service"))
      [servlet# request# response#]
      ((make-service-method ~handler)
         servlet# request# response#))))
