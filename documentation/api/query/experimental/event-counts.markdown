---
title: "PuppetDB 1.3 » API » Experimental » Querying Event Counts"
layout: default
canonical: "/puppetdb/latest/api/query/experimental/event.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[event]: ./event.html
[operator]: ../v2/operators.html

## Routes

### `GET /experimental/event-counts`

This will return count information about all of the resource events matching a given `event` query.  (It builds heavily off of the API for [events](event), so it is a good idea to familiarize yourself with that API before exploring this endpoint.)  For a given object type (resource, class, node), you can retrieve counts of the number of events on the objects of that type that had a status of `success`, `failure`, `noop`, or `skip`.

#### Parameters

* `query`: Required. A JSON array of query predicates for filtering the actual events that should be counted.  The value should be in prefix form (the standard
 `["<OPERATOR>", "<FIELD>", "<VALUE>"]` format), conforming to the format described in the [events](event) endpoint documentation.

* `summarize-by`: Required.  A string specifying which type of object you'd like to see counts for.  Supported values are `resource`, `containing-class`, and `certname`.  So, for example, specifying `containing-class` will cause the query to return a list of maps; each map will contain a class name along with the counts of `failures`, `successes`, `noops`, and `skips` that occurred on the class.

* `last-run-only`: Optional.  A boolean indicating whether or not to limit the results to only include events from the most recent puppet run on each node.  Defaults to `false`.

* `count-by`: Optional.  Specifies what type of object is counted when building up the counts of `successes`, `failures`, etc.  Supported values are `resource` and `node`; default is `resource`.  (e.g.: for a query with `summarize-by=containing-class` and `count-by=resource`, you'll get the number of resources that failed/succeeded/etc., broken down by class.  For a query with `summarize-by=containing-class` and `count-by=node`, you'll get the number of *nodes* that had *at least one resource* with a success/failure/etc. event, broken down by class.

 * `aggregate`: Optional.  A boolean specifying whether or not to aggregate the counts across the entire result set.  (e.g., if `true`, and `summarize-by` is set to `certname`, then rather than returning a list of maps where each map contains counts for a node, we'll return a single map that contains the total number of nodes that had *at least one* success, failure, etc.)  Defaults to `false`.

 * `event-count-query`: Optional.  A JSON array of query predicates for filtering the actual event count data that will be returned.  The value should be in prefix form (the standard `["<OPERATOR>", "<FIELD>", "<VALUE>"]` format).

The `event-count-query` parameter is described by the following grammar:

    event-count-query: [ {match} {field} {value} ] | [ {inequality} {field} {value} ]
    field:          FIELD (conforming to [Fields](#fields) specification below)
    value:          integer
    match:          "="
    inequality:     ">" | ">=" | "<" | "<="

For example, to only return counts for subjects that have at least one failure, the JSON query structure would be:

    [">", "failures", 0]

##### Operators

See [the Operators page](./operators.html) for more detailed information about operators.
Note that only the equality ("=") and inequality operators (`<`, `>`, `<=`, `>=`) are supported for the `event-count-query`.

##### Fields

`FIELD` may be any of the following.  All fields support both equality and inequality
operators.

`successes`: the number of successful events for the subject

`failures`: the number of failed events for the subject

`noops`: the number of no-op events for the subject

`skips`: the number of skip events for the subject

##### Paging and Sorting

* `page-size`: Optional.  If specified, this should be an integer value that will determine the maximum number of results to be returned by the query.

* `start-offset`: Optional, must be used in conjunction with `page-size`.  If specified, this should be an integer value that indicates the offset of the first result that should be returned.  (e.g., a `start-offset` of 10 and a `page-size` of 10 would effectively return results 10-20 for the query.)

* `order-by`: Optional, determines the sort order that the results should
be returned in.  Value must be a string, whose contents are a comma-separated list of field names to sort by.  Also supports the "DESC" keyword to sort a field in descending order.  Supported field names are: `certname`/`resource_type`/`resource_title`/`containing_class` (depending on the value you've provided for `summarize-by`), plus `successes`, `failures`, `noops`, `skips`.

#### Response format

##### Response headers

For any request that triggers a paged query (via `start-offset` and `page-size` query parameters), the response will include the following header:

 * `X-Records` : an integer value indicating the total number of results that would be returned if the query had been issued without paging.

##### Response body

 The response is a JSON array of maps.  Each map contains the counts of events that matched the input parameters.  The events are counted based on their statuses: `successes`, `failures`, `noops`, `skips`.

The maps also contain data about which object the events occurred on; this will vary based on the value you specified for `summarize-by`.

For `summarize-by=certname`, each result will contain a `certname` key.

    [ {
      "certname" : "dhcp97.eric.backline.puppetlabs.net",
      "failures" : 0,
      "successes" : 139,
      "noops" : 0,
      "skips" : 0
    }, {
      "certname" : "dhcp54.eric.backline.puppetlabs.net",
      "failures" : 0,
      "successes" : 139,
      "noops" : 0,
      "skips" : 0
    } ]

For `summarize-by=resource`, each result will contain `resource_type` and `resource_title` keys:

    [ {
      "resource_type" : "File",
      "resource_title" : "authz_user.load symlink",
      "failures" : 0,
      "successes" : 49,
      "noops" : 0,
      "skips" : 0
    }, {
      "resource_type" : "File",
      "resource_title" : "authz_default.load symlink",
      "failures" : 0,
      "successes" : 49,
      "noops" : 0,
      "skips" : 0
    } ]

For `summarize-by=containing-class`, each result will contain a `containing_class` key:

    [ {
      "containing_class" : "Ntp::Config",
      "failures" : 0,
      "successes" : 49,
      "noops" : 0,
      "skips" : 0
    }, {
      "containing_class" : "Apache::Mod::Worker",
      "failures" : 0,
      "successes" : 98,
      "noops" : 0,
      "skips" : 0
    } ]

Finally, if `aggregate=true`, the counts are aggregated across all of the objects of the `summarize-by` type, so there will only be a single result and it will only contain keys for the counts themselves, plus an extra key `total` that indicates the total number of subjects that had an event of any type.

    [ {
      "successes" : 22,
      "failures" : 1,
      "noops" : 0,
      "skips" : 1,
      "total" : 22
    } ]

#### Example

[You can use `curl`][curl] to query information about event counts like so:

    curl -G 'http://localhost:8080/experimental/event-counts' --data-urlencode 'query=["and", [">", "timestamp", "2010-07-02T20:00:00-07:00"], ["<", "timestamp", "2015-07-03T07:00:00-07:00"]]' --data-urlencode 'summarize-by=containing-class' --data-urlencode 'start-offset=0' --data-urlencode 'page-size=10' --data-urlencode 'order-by=failures desc' --data-urlencode 'event-count-query=[">", "failures", 0]'

    curl -G 'http://localhost:8080/experimental/event-counts' --data-urlencode 'query=["and", [">", "timestamp", "2010-07-02T20:00:00-07:00"], ["<", "timestamp", "2015-07-03T07:00:00-07:00"]]' --data-urlencode 'summarize-by=containing-class' --data-urlencode 'start-offset=0' --data-urlencode 'page-size=10' --data-urlencode 'order-by=failures desc' --data-urlencode 'aggregate=true'

