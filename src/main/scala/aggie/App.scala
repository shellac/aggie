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

object App extends App {


  implicit def traversableToGroupByOrderedImplicit[A](t: Traversable[A]): GroupByOrderedImplicit[A] =
    new GroupByOrderedImplicit[A](t)

  println( "Hello World!" )

  val store = new Store( "rome.results.tdb" )
  store.load("feeds.ttl")

  val fetcher = new AggieFetcher( new DiskFeedInfoCache("rome.http.cache"),
    store.listSources )

  //fetcher.fetch { (entry, source) =>
  //  store.record( entry, source )
  //}

  store.listRecent.foreach { feeditem =>
    printf("%s\t%s\t%s: ...\n", feeditem.date.get(Calendar.MONTH), feeditem.date.get(Calendar.DAY_OF_MONTH), feeditem.date.getTimeInMillis)
  }

  val toDate = new SimpleDateFormat("EEE, dd MMMMM")

  // Phew! Group list to map Date -> Feed -> item
  val groupedRecent = store.listRecent
    .groupByOrdered(f => toDate.format(f.date.getTime))
    .mapValues(_.groupByOrdered(_.feed))

  groupedRecent.foreach { byDate =>
    printf("%s\n", byDate._1)
    byDate._2.foreach { byFeed =>
      printf("\t\t%s\n", byFeed._1)
      byFeed._2.foreach(f => printf("      %s: %s\n", f.date.getTimeInMillis, "..."))
    }
  }

  val engine = new TemplateEngine
  engine.bindings = List(Binding("helper", "net.rootdev.aggie.Helper", true))
  val output = engine.layout("tmpl.ssp", Map("groupedRecent" -> groupedRecent, "helper" -> new Helper))

  val out = new PrintWriter(new File("foo.html"), "utf-8")
  out.println(output)
  out.close()
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
  } order by DESC(?created) limit 80

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
      ds.getNamedModel("loaded:" + source).add(data)
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
      thing.addLiteral(DCTerms.created, asCalendar(entry.getPublishedDate))
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

  def listRecent: List[FeedItem] = {
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