package net.rootdev.aggie

import scala.collection.JavaConversions._
import org.rometools.fetcher.impl.{ HttpClientFeedFetcher, DiskFeedInfoCache, FeedFetcherCache }
import java.net.URL
import com.sun.syndication.feed.synd.SyndEntry

object App extends App {
  println( "Hello World!" )

  val fetcher = new AggieFetcher( new DiskFeedInfoCache("rome.http.cache"),
    "http://twitter.com/statuses/user_timeline/7169162.rss" )

  fetcher.fetch { entry =>
    printf( "[%s] %s <%s>\n", entry.getPublishedDate, entry.getTitle, entry.getLink )
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