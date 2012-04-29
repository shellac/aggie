package net.rootdev.aggie

import scala.collection.JavaConversions._
import org.rometools.fetcher.impl.{ HttpClientFeedFetcher, DiskFeedInfoCache, FeedFetcherCache }
import java.net.URL
import com.sun.syndication.feed.synd.SyndEntry
import com.hp.hpl.jena.tdb.TDBFactory
import com.hp.hpl.jena.vocabulary.DCTerms
import com.hp.hpl.jena.query.{QueryExecutionFactory, QueryFactory, ReadWrite}

object App extends App {
  println( "Hello World!" )

  val fetcher = new AggieFetcher( new DiskFeedInfoCache("rome.http.cache"),
    "http://twitter.com/statuses/user_timeline/7169162.rss" )

  val store = new Store( "rome.results.tdb" )

  fetcher.fetch { entry =>
    //printf( "[%s] %s <%s>\n", entry.getPublishedDate, entry.getTitle, entry.getLink )
    store.record( entry )
  }

  store.listRecent { (title, link, date) =>
    printf("[%s] %s <%s>\n", date, title, link)
  }
}

class AggieFetcher(feedCache: FeedFetcherCache, thesources: String*) {
  val fetcher = new HttpClientFeedFetcher(feedCache)
  val sources = thesources.map(url => new URL(url))

  def fetch(handler: (SyndEntry) => Unit ) {
    sources.foreach(source => fetchFeed(source, handler))
  }

  def fetchFeed(source: URL, handler: (SyndEntry) => Unit ) {
    val feed = fetcher.retrieveFeed("net.rootdev.aggie; <mailto:damian@apache.org>", source)
    feed.getEntries.foreach( entry => handler.apply(entry.asInstanceOf[SyndEntry]) )
  }
}

class Store(dir: String) {
  val storeDir = dir
  val listRecentQuery = QueryFactory.create("""

  prefix dc: <http://purl.org/dc/terms/>

  select ?created, ?title, ?link {
    graph ?g {
      ?item dc:title ?title ; dc:references ?link ; dc:created ?created
    }
  } order by ?created limit 20

  """)

  def getDataset = TDBFactory.createDataset(dir)

  def record(entry: SyndEntry) {
    val ds = getDataset

    try {
      ds.begin(ReadWrite.WRITE)
      val m = ds.getNamedModel(entry.getSource.getUri)
      val thing = m.createResource(entry.getUri)
      thing.removeProperties()
      thing.addLiteral(DCTerms.created, entry.getPublishedDate)
      thing.addLiteral(DCTerms.modified, entry.getUpdatedDate)
      thing.addProperty(DCTerms.title, entry.getTitle)
      thing.addProperty(DCTerms.references, m.createResource(entry.getLink))
      ds.commit()
    } finally {
      ds.end()
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