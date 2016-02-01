package esc.index

import java.io.{ FileFilter, File }

import scala.Array.canBuildFrom
import scala.collection.immutable.Stream.consWrapper
import scala.io.Source

import akka.actor.actorRef2Scala
import akka.actor.{ Props, ActorSystem, ActorRef, Actor }
import akka.routing.RoundRobinRouter
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global 

object Indexer extends App {

  /////////////// start main /////////////////

  val props = properties(new File("conf/indexer.properties"))
  val server0 = List(props("serverName"),props("indexName")).foldRight("")(_ + "/" + _) //http://localhost:9200/enron/
  val server1 = List(props("serverName"),props("indexName"),props("mappingName")).foldRight("")(_ + "/" + _)
  //http://localhost:9200/enron/mails/

  play.core.server.NettyServer.main(Array())
  indexFiles(props)

  /////////////// end main /////////////////

  def indexFiles(props: Map[String, String]): Unit = {
    val system = ActorSystem("ElasticSearchIndexer")
    val reaper = system.actorOf(Props[Reaper], name = "reaper")
    val master = system.actorOf(Props(new IndexMaster(props, reaper)), name = "master")
    master ! StartMsg
  }

  //////////////// actor and message definitions //////////////////

  sealed trait EscMsg
  case class StartMsg() extends EscMsg
  case class IndexMsg(file: File) extends EscMsg
  case class IndexRspMsg(status: Int) extends EscMsg

  class IndexMaster(props: Map[String, String], reaper: ActorRef)
      extends Actor {
    val numIndexers = props("numIndexers").toInt
    val schema = Class.forName(props("schemaClass")).newInstance.asInstanceOf[Schema]
    val router = context.actorOf(Props(new IndexWorker(props)).withRouter(RoundRobinRouter(numIndexers)))

    var nreqs = 0
    var succs = 0
    var fails = 0

    def createIndex() = sendToServer(server0, """
      {"settings": 
        {"index": 
          {"number_of_shards": %s,
           "number_of_replicas": %s}
      }}""".format(props("numShards"), props("numReplicas")),
      false)

    def createSchema() = sendToServer(server1 + "_mapping","""{ "%s" : { "properties" : %s } }""".format(props("indexName"), schema.mappings), false)

    def receive = {
      case StartMsg => {
        val filefilter = Class.forName(props("filterClass")).newInstance.asInstanceOf[FileFilter]
        val files = walk(new File(props("rootDir"))).filter(f => filefilter.accept(f))
        
        val p = createIndex().flatMap { x =>
          createSchema().map { y => 
              for (file <- files) {
                println("loading file " + file + " to queue")
                nreqs = nreqs + 1
                router ! IndexMsg(file)
              }
          }
        }
      }
      case IndexRspMsg(status) => {
        if (status == 0) succs = succs + 1 else fails = fails + 1
        val processed = succs + fails
        if (processed % 100 == 0)
          println("Processed %d/%d (success=%d, failures=%d)".format(processed, nreqs, succs, fails))
        if (nreqs == processed) {
          println("Processed %d/%d (success=%d, failures=%d)".format(processed, nreqs, succs, fails))
          reaper ! IndexRspMsg(-1)
          context.stop(self)
        }
      }
    }
  }

  class IndexWorker(props: Map[String, String]) extends Actor {

    val parser = Class.forName(props("parserClass")).newInstance.asInstanceOf[Parser]
    val schema = Class.forName(props("schemaClass")).newInstance.asInstanceOf[Schema]

    def addDocument(doc: Map[String, String]) = {
      val json = doc.filter(kv => schema.isValid(kv._1)).map(kv =>
          {
            if (schema.isMultiValued(kv._1))
              Json.toJson(kv._1) -> Json.toJson(kv._2.split(",").map(e => e.trim).toSeq)
            else 
              Json.toJson(kv._1) -> Json.toJson(kv._2)
          }
        ).foldLeft("")((s, e) => s + e._1 + " : " + e._2 + ",")
      //println("JSON to send -> " + json.substring(0, json.length - 1))
      sendToServer(server1, "{" + json.substring(0, json.length - 1) + "}", true)
    }

    def receive = {
      case IndexMsg(file) => {
        val doc = parser.parse(Source.fromFile(file))
        addDocument(doc).map { x => 
            sender ! IndexRspMsg(x)
        }
      }
    }
  }

  class Reaper extends Actor {
    def receive = {
      case IndexRspMsg(-1) => {
        println("Shutting down ElasticSearchIndexer")
        context.system.shutdown
      }
    }
  }

  ///////////////// global functions ////////////////////

  def properties(conf: File): Map[String, String] = {
    Map() ++ Source.fromFile(conf).getLines().toList.
      filter(line => (!(line.isEmpty || line.startsWith("#")))).
      map(line => (line.split("=")(0) -> line.split("=")(1)))
  }

  def walk(root: File): Stream[File] = {
    if (root.isDirectory)
      root #:: root.listFiles.toStream.flatMap(walk(_))
    else root #:: Stream.empty
  }

  def sendToServer(server: String, payload: String,
                   usePost: Boolean) = {
    
    //println("Payload -> " + payload)
    val prom = if (usePost) WS.url(server).post(payload)
    else WS.url(server).put(payload)
    prom.map(response => {
      //println("Response Received -> " + response.body)
      val ttt = Json.parse(response.body)
      //println ("Parsed Value "+ ttt)
      (Json.parse(response.body) \ "created").asOpt[Boolean] match {
        case Some(true) => 0
        case _          => -1
      }
    })
  }
}
