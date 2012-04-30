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

object App extends App {
  println( "Hello World!" )

  val store = new Store( "rome.results.tdb" )
  store.load("feeds.ttl")

  val fetcher = new AggieFetcher( new DiskFeedInfoCache("rome.http.cache"),
    store.listSources )

  fetcher.fetch { (entry, source) =>
    //printf( "[%s] %s <%s>\n", entry.getPublishedDate, entry.getTitle, entry.getLink )
    store.record( entry, source )
  }

  store.listRecent { (title, link, date) =>
    printf("[%s] %s <%s>\n", date, title, link)
  }
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
      case _ => printf("Issue loading <%s>", source)
    }
  }
}

class Store(dir: String) {
  val storeDir = dir
  val listRecentQuery = QueryFactory.create("""

  prefix dc: <http://purl.org/dc/terms/>

  select ?created ?title ?link {
    graph ?g {
      ?item dc:title ?title ; dc:references ?link ; dc:created ?created
    }
  } order by ?created limit 20

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

  def listRecent(handler: (String, String, String) => Unit) {
    val qe = QueryExecutionFactory.create(listRecentQuery, getDataset)
    try {
      val results = qe.execSelect()
      results.foreach { result =>
        handler.apply(
          result.get("?title").asLiteral().getString,
          result.get("?link").asResource().getURI,
          result.get("?created").asLiteral().getLexicalForm
        )
      }
    }
    finally {
      qe.close()
    }
  }
}