package net.rootdev.aggie

import scala.collection.JavaConversions._
import org.rometools.fetcher.impl.{ HttpClientFeedFetcher, DiskFeedInfoCache, FeedFetcherCache }
import java.net.URL
import java.util.{ Date, Calendar }
import com.sun.syndication.feed.synd.SyndEntry
import com.hp.hpl.jena.tdb.TDBFactory
import com.hp.hpl.jena.vocabulary.DCTerms
import com.hp.hpl.jena.query.{QueryExecutionFactory, QueryFactory, ReadWrite}
import com.hp.hpl.jena.util.FileManager
import com.hp.hpl.jena.datatypes.xsd.XSDDateTime
import org.fusesource.scalate._
import java.io.{File, PrintWriter, OutputStreamWriter}
import java.text.{DateFormatSymbols, SimpleDateFormat}
import scopt.mutable.OptionParser

object App extends App {

  var fetch = true
  var devel = false
  var outputFile = "out.html"
  var feedConfig: Option[String] = None

  val parser = new OptionParser("aggie", "0.1") {
    booleanOpt("fetch", "fetch new posts", {v: Boolean => fetch = v})
    booleanOpt("devel", "developer mode (no template caching, no fetch)", {v: Boolean => devel = v; fetch = false})
    opt("c", "feedconfig", "Read feed config from <feedconfig>", {v: String => feedConfig = Some(v)})
    opt("o", "output", "Write output to <output>", {v: String => outputFile = v})
  }

  if (!parser.parse(args)) {
    sys.exit(255)
  }

  implicit def traversableToGroupByOrderedImplicit[A](t: Iterable[A]): GroupByOrderedImplicit[A] =
    new GroupByOrderedImplicit[A](t)

  println( "Starting..." )

  val store = new Store( "rome.results.tdb" )

  if (feedConfig != None) {
    printf("Loading feed config <%s>\n", feedConfig.get)
    store.load(feedConfig.get)
  }

  if (fetch) {
    println("Fetching feeds")
    val fetcher = new AggieFetcher( new DiskFeedInfoCache("rome.http.cache"),
      store.listSources )

    fetcher.fetch { (entry, source) =>
      store.record( entry, source )
    }
  }

  val toDate = new SimpleDateFormat("EEE, dd MMMMM")

  // Phew! Group list to map Date -> Feed -> item
  val groupedRecent = store.listRecent
    .groupByOrdered(f => toDate.format(f.date.getTime))
    .map { case(k,v) => (k, v.groupByOrdered(g => g.feed)) }

  val engine = new TemplateEngine
  engine.workingDirectory = new File("scalate-working")
  engine.allowCaching = true
  engine.allowReload = devel // will reload each time, it seems?
  engine.bindings = List(Binding("helper", "net.rootdev.aggie.Helper", true))
  val output = engine.layout("tmpl.ssp", Map("groupedRecent" -> groupedRecent, "helper" -> new Helper))

  val out = new PrintWriter(new File(outputFile), "utf-8")
  out.println(output)
  out.close()

  println("Finished!")
}

case class FeedItem(title: String, link: String, date: Calendar, feed: String)

class Helper {
  val toTime = new SimpleDateFormat("H:mm")
  def time(cal:Calendar) = toTime.format(cal.getTime)
}

class AggieFetcher(feedCache: FeedFetcherCache, thesources: List[String]) {
  val fetcher = new HttpClientFeedFetcher(feedCache)
  val sources = thesources.map(url => new URL(url))

  def fetch(handler: (SyndEntry, URL) => Unit ) {
    sources.foreach(source => fetchFeed(source, handler))
  }

  def fetchFeed(source: URL, handler: (SyndEntry, URL) => Unit ) {
    try {
      val feed = fetcher.retrieveFeed("net.rootdev.aggie; <mailto:damian@apache.org>", source)
      feed.getEntries.foreach( entry => handler.apply(entry.asInstanceOf[SyndEntry], source) )
    } catch {
      case e => printf("Issue loading <%s>: %s\n", source, e)
    }
  }
}

class Store(dir: String) {
  val storeDir = dir
  val listRecentQuery = QueryFactory.create("""

  prefix dc: <http://purl.org/dc/terms/>

  select ?created ?title ?link ?feedlabel {
    graph ?g {
      ?item dc:title ?title ; dc:references ?link ; dc:created ?created
    }
    graph ?g1 {
      ?g dc:title ?feedlabel
    }
  } order by DESC(?created) limit 200

  """)

  val listSourcesQuery = QueryFactory.create("""

  prefix dc: <http://purl.org/dc/terms/>

  select ?source {
    graph ?g {
      ?source dc:type ?type
    }
  }

  """)

  def load(source: String) {
    val data = FileManager.get().loadModel(source)
    val ds = getDataset
    ds.begin(ReadWrite.WRITE)
    try {
      ds.getNamedModel("loaded:" + source).removeAll().add(data)
      ds.commit()
    } finally {
      ds.end()
    }
  }

  def getDataset = TDBFactory.createDataset(dir)

  def asCalendar(date: Date): Calendar = {
    val cal = Calendar.getInstance()
    cal.setTime(date)
    cal
  }

  def record(entry: SyndEntry, source: URL) {
    val ds = getDataset

    try {
      ds.begin(ReadWrite.WRITE)
      val m = ds.getNamedModel(source.toString)
      val thing = m.createResource(entry.getUri)
      thing.removeProperties()
      if (entry.getPublishedDate != null) thing.addLiteral(DCTerms.created, asCalendar(entry.getPublishedDate))
      if (entry.getUpdatedDate != null) thing.addLiteral(DCTerms.modified, asCalendar(entry.getUpdatedDate))
      thing.addProperty(DCTerms.title, entry.getTitle)
      thing.addProperty(DCTerms.references, m.createResource(entry.getLink))
      ds.commit()
    } finally {
      ds.end()
    }

  }

  def listSources: List[String] = {
    val qe = QueryExecutionFactory.create(listSourcesQuery, getDataset)
    try {
      val results = qe.execSelect()
      results.map( r => r.getResource("?source").getURI ).toList
    }
    finally {
      qe.close()
    }
  }

  def listRecent: Iterable[FeedItem] = {
    val qe = QueryExecutionFactory.create(listRecentQuery, getDataset)
    try {
      val results = qe.execSelect()
      results.map { result =>
        FeedItem(
          result.get("?title").asLiteral().getString,
          result.get("?link").asResource().getURI,
          result.get("?created").asLiteral().getValue.asInstanceOf[XSDDateTime].asCalendar(),
          result.get("?feedlabel").asLiteral().getString
        )
      }.toList
    }
    finally {
      qe.close()
    }
  }
}
