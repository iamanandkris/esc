esc
===

What the project does, the problem it solves, etc.
--------------------------------------------------

A search/index client for [ElasticSearch](http://www.elasticsearch.org/) written using the tools in the [Typesafe Stack](http://typesafe.com/stack) - Scala, Akka and Play. 

The client communicates with ElasticSearch using its JSON/HTTP REST interface. The indexing subsystem is a command line application that uses Akka actors to parallelize the parsing and indexing of a corpus of documents across multiple Actors. The search subsystem is a Play2 web application that provides a form based search interface and results in either HTML (for human consumption and experimentation) and JSON (the native output).

The documents used for development and testing are taken from the [Enron Email DataSet](http://www.cs.cmu.edu/~enron/).

The project is aimed at a developer or search QA engineer to try out various qieries against an ElasticSearch index and quickly and easily get feedback about the results. ElasticSearch queries are written as JSON documents, which is also available in the results of this tool. The JSON document can be copy-pasted into the developer's search code.

Why it does it this way, benefits and drawbacks
-----------------------------------------------

The benefit of using this application is convenience. Using the form to experiment with search parameters is more convenient than using curl on the command line. It is also more suitable for less technical users who nevertheless need to interact with search results prior to them being available in the application (eg search QA engineers).

The drawback is that the form is not as flexible as hand-crafted JSON queries. The ElasticSearch Query DSL is very rich, and the richness has not been fully captured in the application because it would make the form very complex and negate the convenience of having this kind of application.

High-level design and architecture
----------------------------------

### Index ###

The index subsystem is built using Akka actors. There is one reaper actor and one master actor which spawns a configurable number of worker actors. The master actor walks the directory tree of files and writes out the files into the worker actor's mailboxes using a round-robin strategy. Each worker actor parses the file into a set of name-value pairs, adds the document to the ElasticSearch index using a JSON over HTTP POST request. When all the files are processed, the worker sends a message to the reaper actor and shuts itself down, including the worker actors. The reaper actor sends a signal to shut the entire application down (this does not seem to work, the signal is received but the application has to be terminated from the console with a CTRL-C).

### Search ###

The search subsystem is a Play2 web application. It has a single form which is responsible for collecting the search parameters, and two output pages. The first one returns an HTML formatted (human readable) view of the search results, as well as the query JSON used to generate the results, and the second one shows the raw JSON output from ElasticSearch.

Instructions that detail how the project is compiled, deployed and used.
------------------------------------------------------------------------

### Index ###

The index subsystem uses sbt as its build system. To run, follow these steps:

1. Download and install ElasticSearch - if you already have ElasticSearch running, you can skip this step.
2. Configure ElasticSearch - at the minimum you will need to set the cluster.name and network.bind_host in config/elasticsearch.yml. Once again, if you have ElasticSearch running already, you can skip this step.
3. If you going to use the Enron dataset, then download and expand it somewhere on your local disk.
4. Update thes values in index/config/indexer.properties.
  * rootDir - the directory where your Enron maildir can be found.
  * numIndexers - the number of indexing worker actors you would like to start. I set it to the number of CPUs I have on my machine.
  * indexName - the name of the Index you want for your dataset. ElasticSearch allows multiple indexes to coexist under the same server. 
  * mappingName - the name of the schema for this index. Each index can have a schema (mapping) associated with them.
  * numShards - if you are pointing to an existing ElasticSearch instance, check with your ES Admin, otherwise keep this as 1 if you just built your own in step 1.
  * numReplicas - if you are pointing to an existing ElasticSearch instance, check with your ES Admin, otherwise keep this as 1 if you just built your own in step 1.
  * serverName - the URL to your ElasticSearch server. If you built your own in step 1, keep the default value. 
5. If you are _not_ planning on using the Enron dataset, then please see "Using your own Data Corpus" below.
6. The index application runs using sbt, so you can start the indexing by running "cd index; sbt run" from the command line.

### Using your own Data Corpus ###

The index application provides a simple traits based plugin mechanism to support data sets other than the Enron data. The traits that need to be extended are in the ExtensionPoints.scala file. The Enron dataset is an example of this extension mechanism, the implementations for the Enron dataset are in Enron.scala. For a new dataset, use Enron.scala as an example and build your own implementations, then update the class names for the implementations in conf/indexer.properties.
  * parserClass - full class name for the Parser implementation. This defines a function that transforms a Source into a Map of field name, field value pairs.
  * filterClass - full class name for the file filter implementation. This is a holder of a function that converts a File to a boolean indicating if it should be processed by the parser or not.
  * schemaClass - full class name for the schema implementation. This returns the payload for the ElasticSearch put_mapping call for this dataset.


### Search ###

The search subsystem uses play as its build subsystem. To run, follow these steps.

1. Download and install [Play2](ihttp://www.playframework.org/).
2. Point the application to the ElasticSearch instance. If you just installed ElasticSearch fresh for this application, then the default is fine, otherwise ask your ES Admin what the value should be.
3. Start the application using "cd search; play run". This will start the Play2 server on port 9000.
4. On the browser, enter http://localhost:9000. You should see a form that prompts for various parameters that you would pass to an ElasticSearch index in order to query it.
5. You can choose to see human readable HTML results or raw JSON output returned by ElasticSearch based on the Output Type paramter (HTML or JSON).

More Information
----------------

For more/related information, you may also want to take a look at my blog posts about these two applications.
* [Indexing into ElasticSearch with Akka and Scala](http://sujitpal.blogspot.com/2012/11/indexing-into-elasticsearch-with-akka.html)
* [An ElasticSearch Web Client with Scala and Play2](http://sujitpal.blogspot.com/2012/11/an-elasticsearch-web-client-with-scala.html) 

