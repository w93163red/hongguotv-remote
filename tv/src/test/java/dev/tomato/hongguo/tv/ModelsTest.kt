package dev.tomato.hongguo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    private fun card(id: String) = SeriesCard(seriesId = id, title = "示例短剧 $id")
    private fun ep(i: Int) = Episode(index = i, vid = "v$i")

    @Test fun keysWithNothingOpen() {
        val now = 10_000L
        fun act(k: RemoteKey, repeat: Int = 0) = playerAction(Overlay.None, k, repeat, now, backArmedAt = 0)
        assertEquals(PlayerAction.Prev, act(RemoteKey.Up))
        assertEquals(PlayerAction.Next, act(RemoteKey.Down))
        assertEquals(PlayerAction.Seek(-10_000), act(RemoteKey.Left))
        assertEquals(PlayerAction.Seek(10_000), act(RemoteKey.Right))
        assertEquals(PlayerAction.Seek(20_000), act(RemoteKey.Right, repeat = 4))
        assertEquals(PlayerAction.Seek(60_000), act(RemoteKey.Right, repeat = 400))
        assertEquals(PlayerAction.Show(Overlay.Controls), act(RemoteKey.Ok))
        assertEquals(PlayerAction.Show(Overlay.Episodes), act(RemoteKey.Menu))
        assertEquals(PlayerAction.Show(Overlay.SideNav), act(RemoteKey.Back))
        assertEquals(PlayerAction.PlayPause, act(RemoteKey.PlayPause))
        assertEquals(PlayerAction.Next, act(RemoteKey.Next))
        assertEquals(PlayerAction.Prev, act(RemoteKey.Prev))
    }

    @Test fun backWalksControlsThenSideNavThenExits() {
        assertEquals(PlayerAction.Show(Overlay.None), playerAction(Overlay.Controls, RemoteKey.Back, 0, 0, 0))
        assertEquals(PlayerAction.CloseNavArmExit, playerAction(Overlay.SideNav, RemoteKey.Back, 0, 5_000, 0))
        assertEquals(PlayerAction.Exit, playerAction(Overlay.None, RemoteKey.Back, 0, 6_999, 5_000))
        assertEquals(PlayerAction.Show(Overlay.SideNav), playerAction(Overlay.None, RemoteKey.Back, 0, 7_000, 5_000))
        assertEquals(PlayerAction.Pass, playerAction(Overlay.None, RemoteKey.Back, 3, 6_000, 5_000))
    }

    @Test fun keysInsideOverlays() {
        assertEquals(PlayerAction.Pass, playerAction(Overlay.Controls, RemoteKey.Left, 0, 0, 0))
        assertEquals(PlayerAction.Pass, playerAction(Overlay.Controls, RemoteKey.Ok, 0, 0, 0))
        assertEquals(PlayerAction.Show(Overlay.Episodes), playerAction(Overlay.Controls, RemoteKey.Menu, 0, 0, 0))
        assertEquals(PlayerAction.ToggleOrder, playerAction(Overlay.Episodes, RemoteKey.Menu, 0, 0, 0))
        assertEquals(PlayerAction.Show(Overlay.None), playerAction(Overlay.Episodes, RemoteKey.Back, 0, 0, 0))
        assertEquals(PlayerAction.Pass, playerAction(Overlay.Episodes, RemoteKey.Down, 0, 0, 0))
        assertEquals(PlayerAction.Show(Overlay.Controls), playerAction(Overlay.Speed, RemoteKey.Back, 0, 0, 0))
        assertEquals(PlayerAction.Show(Overlay.Controls), playerAction(Overlay.More, RemoteKey.Back, 0, 0, 0))
        assertEquals(PlayerAction.Show(Overlay.None), playerAction(Overlay.SideNav, RemoteKey.Right, 0, 0, 0))
        assertEquals(PlayerAction.Pass, playerAction(Overlay.SideNav, RemoteKey.Down, 0, 0, 0))
        assertEquals(PlayerAction.PlayPause, playerAction(Overlay.SideNav, RemoteKey.PlayPause, 0, 0, 0))
    }

    @Test fun queueNavigation() {
        val feed = PlayQueue.Feed(listOf(card("1"), card("2"), card("3")), 0)
        assertNull(feed.moved(-1))
        assertEquals(PlayQueue.Feed(feed.series, 1), feed.moved(1))
        assertNull(feed.moved(1)!!.moved(1)!!.moved(1))

        val series = PlayQueue.Series("s", listOf(ep(1), ep(2)), 1)
        assertEquals(PlayQueue.Series("s", series.episodes, 0), series.moved(-1))
        assertNull(series.moved(1))
    }

    @Test fun endOfEpisode() {
        val feed = PlayQueue.Feed(listOf(card("1"), card("2")), 0)
        assertEquals(AfterEnd.NextEpisode(4), afterEnd(feed, epIdx = 3, epCount = 20))
        assertEquals(AfterEnd.NextQueue(PlayQueue.Feed(feed.series, 1)), afterEnd(feed, epIdx = 19, epCount = 20))
        assertEquals(AfterEnd.Stop, afterEnd(feed.copy(index = 1), epIdx = 19, epCount = 20))

        val series = PlayQueue.Series("s", listOf(ep(1), ep(2)), 0)
        assertEquals(AfterEnd.NextQueue(series.copy(index = 1)), afterEnd(series, 0, 2))
        assertEquals(AfterEnd.Stop, afterEnd(series.copy(index = 1), 1, 2))
    }

    @Test fun mergeKeepsNewerAndFillsTitles() {
        val local = listOf(
            Watched("a", "本地A", "ca", 40, ep = 5, positionMs = 1_000, updatedAt = 300),
            Watched("b", "本地B", "cb", 20, ep = 2, positionMs = 2_000, updatedAt = 100),
        )
        val cloud = listOf(
            CloudItem("a", ep = 3, posMs = 9_000, ts = 200, title = "云A"),
            CloudItem("b", ep = 7, posMs = 4_000, ts = 400),
            CloudItem("c", ep = 1, posMs = 0, ts = 250, title = "云C", cover = "cc"),
        )
        assertEquals(
            listOf(
                Watched("b", "本地B", "cb", 20, ep = 7, positionMs = 4_000, updatedAt = 400),
                Watched("a", "本地A", "ca", 40, ep = 5, positionMs = 1_000, updatedAt = 300),
                Watched("c", "云C", "cc", 0, ep = 1, positionMs = 0, updatedAt = 250),
            ),
            mergeHistory(local, cloud),
        )
    }

    @Test fun parsesRankResponse() {
        val body = """{"board":"hot","name":"漫剧热播榜","items":[{"rank":1,"series_id":"7400000000000000001",
            "title":"示例短剧 01","episode_cnt":62,"score":"9.1","play_cnt":12345678,"hot":"1.2万热度",
            "copyright":"x","cover":"https://p3.byteimg.com/c/1.heic","intro":"简介"}]}"""
        val list = ApiJson.decodeFromString<CardList>(body)
        assertEquals(
            SeriesCard("7400000000000000001", "示例短剧 01", 62, "https://p3.byteimg.com/c/1.heic", "简介", "9.1", 12345678, "1.2万热度"),
            list.items.single(),
        )
    }

    @Test fun parsesEpisodesFiltersAndAuth() {
        val eps = ApiJson.decodeFromString<EpisodesResp>(
            """{"meta":{"series_id":"74","title":"示例短剧 02","intro":"好看","episode_cnt":2,"status":"完结",
            "play_cnt":900,"followed_cnt":3,"create_time":0,"cover":"c","category":["都市"],"celebrities":[]},
            "episodes":[{"index":1,"vid":"v01","title":"第1集","duration":20.5,"cover":"","comment_count":0,"digged_count":0},
            {"index":2,"vid":"v02","title":"第2集","duration":null,"cover":""}]}""",
        )
        assertEquals(SeriesMeta("74", "示例短剧 02", "好看", 2, "完结", 900, "c"), eps.meta)
        assertEquals(listOf(Episode(1, "v01", "第1集", 20.5), Episode(2, "v02", "第2集", 0.0)), eps.episodes)

        val filters = ApiJson.decodeFromString<Filters>(
            """{"genre":"short_play","rows":[{"type":"genre","row_name":"体裁","items":[{"id":"short_play","name":"短剧"}]},
            {"type":"category_dim_theme","row_name":"主题","selection_type":1,"items":[{"id":"cate_1","name":"玄幻"},{"id":"cate_2","name":"都市"}]}]}""",
        )
        assertEquals(listOf("玄幻", "都市"), filters.themes)

        assertEquals(
            AuthStatus("confirmed", AuthUser("示例用户", "")),
            ApiJson.decodeFromString<AuthStatus>("""{"status":"confirmed","user":{"name":"示例用户","avatar":""}}"""),
        )
        assertEquals(Me(false, null), ApiJson.decodeFromString<Me>("""{"logged_in":false}"""))
        assertEquals(
            CloudHistory(true, listOf(CloudItem("74", 3, 15000, 1700000000000, "示例短剧 02", "c"))),
            ApiJson.decodeFromString<CloudHistory>(
                """{"logged_in":true,"total":1,"items":[{"sid":"74","ep":3,"pos_ms":15000,"ts":1700000000000,"title":"示例短剧 02","cover":"c"}]}""",
            ),
        )
    }

    @Test fun ranksOfThirty() {
        assertEquals(listOf(0 until 30, 30 until 60, 60 until 80), episodeRanges(80))
        assertEquals(listOf(0 until 20), episodeRanges(20))
    }
}
