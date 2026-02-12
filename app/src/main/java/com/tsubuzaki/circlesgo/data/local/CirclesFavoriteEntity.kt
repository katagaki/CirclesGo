package com.tsubuzaki.circlesgo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tsubuzaki.circlesgo.api.catalog.UserFavorites
import com.tsubuzaki.circlesgo.api.catalog.WebCatalogCircle
import com.tsubuzaki.circlesgo.api.catalog.WebCatalogFavorite

@Entity(tableName = "circles_favorites")
data class CirclesFavoriteEntity(
    @PrimaryKey
    val webCatalogID: Int,

    // Circle fields
    val circleName: String,
    val circleNameKana: String,
    val circlemsID: String,
    val cutURL: String,
    val cutBaseURL: String,
    val cutWebURL: String,
    val cutWebUpdateDate: String,
    val genre: String,
    val url: String,
    val pixivURL: String,
    val twitterURL: String,
    val clipStudioURL: String,
    val niconicoURL: String,
    val tag: String,
    val circleDescription: String,
    val onlineStoresJson: String, // Serialized JSON for online stores
    val circleUpdateID: String,
    val circleUpdateDate: String,

    // Favorite fields
    val favoriteCircleName: String,
    val color: Int,
    val memo: String?,
    val free: String?,
    val favoriteUpdateDate: String
) {
    companion object {
        fun fromFavoriteItem(
            webCatalogID: Int,
            item: UserFavorites.Response.FavoriteItem
        ): CirclesFavoriteEntity {
            val storesJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(WebCatalogCircle.OnlineStore.serializer()),
                item.circle.onlineStores
            )
            return CirclesFavoriteEntity(
                webCatalogID = webCatalogID,
                circleName = item.circle.name,
                circleNameKana = item.circle.nameKana,
                circlemsID = item.circle.circlemsID,
                cutURL = item.circle.cutURL,
                cutBaseURL = item.circle.cutBaseURL,
                cutWebURL = item.circle.cutWebURL,
                cutWebUpdateDate = item.circle.cutWebUpdateDate,
                genre = item.circle.genre,
                url = item.circle.url,
                pixivURL = item.circle.pixivURL,
                twitterURL = item.circle.twitterURL,
                clipStudioURL = item.circle.clipStudioURL,
                niconicoURL = item.circle.niconicoURL,
                tag = item.circle.tag,
                circleDescription = item.circle.circleDescription,
                onlineStoresJson = storesJson,
                circleUpdateID = item.circle.updateID,
                circleUpdateDate = item.circle.updateDate,
                favoriteCircleName = item.favorite.circleName,
                color = item.favorite.color,
                memo = item.favorite.memo,
                free = item.favorite.free,
                favoriteUpdateDate = item.favorite.updateDate
            )
        }
    }

    fun toFavoriteItem(): UserFavorites.Response.FavoriteItem {
        val stores = try {
            kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(WebCatalogCircle.OnlineStore.serializer()),
                onlineStoresJson
            )
        } catch (e: Exception) {
            emptyList()
        }

        return UserFavorites.Response.FavoriteItem(
            circle = WebCatalogCircle(
                webCatalogID = webCatalogID,
                name = circleName,
                nameKana = circleNameKana,
                circlemsID = circlemsID,
                cutURL = cutURL,
                cutBaseURL = cutBaseURL,
                cutWebURL = cutWebURL,
                cutWebUpdateDate = cutWebUpdateDate,
                genre = genre,
                url = url,
                pixivURL = pixivURL,
                twitterURL = twitterURL,
                clipStudioURL = clipStudioURL,
                niconicoURL = niconicoURL,
                tag = tag,
                circleDescription = circleDescription,
                onlineStores = stores,
                updateID = circleUpdateID,
                updateDate = circleUpdateDate
            ),
            favorite = WebCatalogFavorite(
                webCatalogID = webCatalogID,
                circleName = favoriteCircleName,
                color = color,
                memo = memo,
                free = free,
                updateDate = favoriteUpdateDate
            )
        )
    }
}
