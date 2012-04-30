# Extract contacts from <http://friendbinder.com/list_friends.php>
# Output at turtle (will need editing)

require 'rubygems'
require 'nokogiri'
require 'open-uri'

doc = Nokogiri::HTML(open('friends.html'))

puts '@prefix dc: <http://purl.org/dc/terms/> .'

doc.xpath("//a[@class='ext']").each do |link|
  href = URI.unescape(link['href'][12..-1])
  STDERR.puts "Fetch <#{href}>"
  begin
    rss_source = Nokogiri::HTML(open(href))
    rss_source_uri = URI.parse(rss_source)
    title = rss_source.xpath("//title").text.strip
    STDERR.puts title.inspect
    rss_source.xpath("//link[@rel='alternate']").each do |feed|
      feed_uri = rss_source_uri + feed['href']
      STDERR.puts "    <#{feed_uri}> (#{feed['type']})"
      puts "<#{feed_uri}>"
      puts "  dc:type \"#{feed['type']}\" ;"
      puts "  dc:title \"#{title}\" ."
    end
  rescue => e
    STDERR.puts "    #{href} went bang"
  end
end