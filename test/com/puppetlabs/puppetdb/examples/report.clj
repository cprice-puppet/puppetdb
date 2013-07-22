(ns com.puppetlabs.puppetdb.examples.report
  (:require [clj-time.coerce :as coerce]))

(def reports
  {:basic
   {:certname               "foo.local"
    :puppet-version         "3.0.1"
    :report-format          3
    :configuration-version  "a81jasj123"
    :start-time             "2011-01-01T12:00:00-03:00"
    :end-time               "2011-01-01T12:10:00-03:00"
    :resource-events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname or containment-class on input, but they will have one on output.
    ;;  To make it easier to test output, we've included them here.  We also include a
    ;;  `:test-id` field to make it easier to reference individual events during
    ;;  testing.  All of these are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
        [{:test-id          1
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2011-01-01T12:00:01-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yo"
          :containment-path nil
          :containing-class nil
          :property         "message"
          :new-value        "notify, yo"
          :old-value        ["what" "the" "woah"]
          :message          "defined 'message' as 'notify, yo'"
          :file             "foo"
          :line             1}
         {:test-id          2
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2011-01-01T12:00:03-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yar"
          :containment-path []
          :containing-class nil
          :property         "message"
          :new-value        {"absent" 5}
          :old-value        {"absent" true}
          :message          "defined 'message' as 'notify, yo'"
          :file             nil
          :line             nil}
         {:test-id          3
          :certname         "foo.local"
          :status           "skipped"
          :timestamp        "2011-01-01T12:00:02-03:00"
          :resource-type    "Notify"
          :resource-title   "hi"
          :containment-path ["Foo" "" "Bar[Baz]"]
          :containing-class "Foo"
          :property         nil
          :new-value        nil
          :old-value        nil
          :message          nil
          :file             "bar"
          :line             2}]
          }})
