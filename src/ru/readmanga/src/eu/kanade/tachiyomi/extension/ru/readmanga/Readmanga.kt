package eu.kanade.tachiyomi.extension.ru.readmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class Readmanga : ParsedHttpSource() {

    override val id: Long = 5

    override val name = "Readmanga"

    override val baseUrl = "http://readmanga.me"

    override val lang = "ru"

    override val supportsLatest = true

    override fun popularMangaSelector() = "div.desc"

    override fun latestUpdatesSelector() = "div.desc"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/list?sortType=rate&offset=${70 * (page - 1)}&max=70", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/list?sortType=updated&offset=${70 * (page - 1)}&max=70", headers)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("h3 > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun popularMangaNextPageSelector() = "a.nextLink"

    override fun latestUpdatesNextPageSelector() = "a.nextLink"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genres = filters.filterIsInstance<Genre>().map { it.id + arrayOf("=", "=in", "=ex")[it.state] }.joinToString("&")
        return GET("$baseUrl/search?q=$query&$genres", headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    // max 200 results
    override fun searchMangaNextPageSelector() = null

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.leftContent").first()

        val manga = SManga.create()
        manga.author = infoElement.select("span.elem_author").first()?.text()
        manga.genre = infoElement.select("span.elem_genre").text().replace(" ,", ",")
        manga.description = infoElement.select("div.manga-description").text()
        manga.status = parseStatus(infoElement.html())
        manga.thumbnail_url = infoElement.select("img").attr("data-full")
        return manga
    }

    private fun parseStatus(element: String): Int {
        when {
            element.contains("<h3>Запрещена публикация произведения по копирайту</h3>") -> return SManga.LICENSED
            element.contains("<h1 class=\"names\"> Сингл") || element.contains("<b>Перевод:</b> завершен") -> return SManga.COMPLETED
            element.contains("<b>Перевод:</b> продолжается") -> return SManga.ONGOING
            else -> return SManga.UNKNOWN
        }
    }

    override fun chapterListSelector() = "div.chapters-link tbody tr"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href") + "?mature=1")
        chapter.name = urlElement.text().replace(" новое", "")
        chapter.date_upload = element.select("td:eq(1)").first()?.text()?.let {
            SimpleDateFormat("dd/MM/yy", Locale.US).parse(it).time
        } ?: 0
        return chapter
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""\s([0-9]+)(\s-\s)([0-9]+)\s*""")
        val extra = Regex("""\s([0-9]+\sЭкстра)\s*""")
        val single = Regex("""\sСингл\s*""")
        when {
            basic.containsMatchIn(chapter.name) -> {
                basic.find(chapter.name)?.let {
                    val number = it.groups[3]?.value!!
                    chapter.chapter_number = number.toFloat()
                }
            }
            extra.containsMatchIn(chapter.name) -> // Extra chapters doesn't contain chapter number
                chapter.chapter_number = -2f
            single.containsMatchIn(chapter.name) -> // Oneshoots, doujinshi and other mangas with one chapter
                chapter.chapter_number = 1f
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body()!!.string()
        val beginIndex = html.indexOf("rm_h.init( [")
        val endIndex = html.indexOf("], 0, false);", beginIndex)
        val trimmedHtml = html.substring(beginIndex, endIndex)

        val p = Pattern.compile("'.+?','.+?',\".+?\"")
        val m = p.matcher(trimmedHtml)

        val pages = mutableListOf<Page>()

        var i = 0
        while (m.find()) {
            val urlParts = m.group().replace("[\"\']+".toRegex(), "").split(',')
            pages.add(Page(i++, "", urlParts[1] + urlParts[0] + urlParts[2]))
        }
        return pages
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }

    override fun imageUrlParse(document: Document) = ""

    private class Genre(name: String, val id: String) : Filter.TriState(name)

    /* [...document.querySelectorAll("tr.advanced_option:nth-child(1) > td:nth-child(3) span.js-link")].map((el,i) => {
    *  const onClick=el.getAttribute('onclick');const id=onClick.substr(31,onClick.length-33);
    *  return `Genre("${el.textContent.trim()}", "${id}")` }).join(',\n')
    *  on http://readmanga.me/search
    */
    override fun getFilterList() = FilterList(
            Genre("арт", "el_5685"),
            Genre("боевик", "el_2155"),
            Genre("боевые искусства", "el_2143"),
            Genre("вампиры", "el_2148"),
            Genre("гарем", "el_2142"),
            Genre("гендерная интрига", "el_2156"),
            Genre("героическое фэнтези", "el_2146"),
            Genre("детектив", "el_2152"),
            Genre("дзёсэй", "el_2158"),
            Genre("додзинси", "el_2141"),
            Genre("драма", "el_2118"),
            Genre("игра", "el_2154"),
            Genre("история", "el_2119"),
            Genre("киберпанк", "el_8032"),
            Genre("кодомо", "el_2137"),
            Genre("комедия", "el_2136"),
            Genre("махо-сёдзё", "el_2147"),
            Genre("меха", "el_2126"),
            Genre("мистика", "el_2132"),
            Genre("научная фантастика", "el_2133"),
            Genre("повседневность", "el_2135"),
            Genre("постапокалиптика", "el_2151"),
            Genre("приключения", "el_2130"),
            Genre("психология", "el_2144"),
            Genre("романтика", "el_2121"),
            Genre("самурайский боевик", "el_2124"),
            Genre("сверхъестественное", "el_2159"),
            Genre("сёдзё", "el_2122"),
            Genre("сёдзё-ай", "el_2128"),
            Genre("сёнэн", "el_2134"),
            Genre("сёнэн-ай", "el_2139"),
            Genre("спорт", "el_2129"),
            Genre("сэйнэн", "el_2138"),
            Genre("трагедия", "el_2153"),
            Genre("триллер", "el_2150"),
            Genre("ужасы", "el_2125"),
            Genre("фантастика", "el_2140"),
            Genre("фэнтези", "el_2131"),
            Genre("школа", "el_2127"),
            Genre("этти", "el_2149"),
            Genre("юри", "el_2123")
    )
}