package com.mypeople.walkolutionodometer

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.*

// Data models
@JsonClass(generateAdapter = true)
data class StravaTokenResponse(
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_at") val expiresAt: Long,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "access_token") val accessToken: String,
    val athlete: StravaAthlete? = null
)

@JsonClass(generateAdapter = true)
data class StravaAthlete(
    val id: Long,
    @Json(name = "firstname") val firstName: String?,
    @Json(name = "lastname") val lastName: String?
)

@JsonClass(generateAdapter = true)
data class StravaActivity(
    val id: Long,
    val name: String,
    val distance: Float,
    @Json(name = "elapsed_time") val elapsedTime: Int,
    @Json(name = "sport_type") val sportType: String
)

// API interface
interface StravaApi {

    // Exchange authorization code for tokens
    @POST("oauth/token")
    @FormUrlEncoded
    suspend fun exchangeToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String = "authorization_code"
    ): Response<StravaTokenResponse>

    // Refresh access token
    @POST("oauth/token")
    @FormUrlEncoded
    suspend fun refreshToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type") grantType: String = "refresh_token"
    ): Response<StravaTokenResponse>

    // Create activity
    @POST("api/v3/activities")
    @FormUrlEncoded
    suspend fun createActivity(
        @Header("Authorization") authorization: String,
        @Field("name") name: String,
        @Field("sport_type") sportType: String,
        @Field("start_date_local") startDateLocal: String,
        @Field("elapsed_time") elapsedTime: Int,
        @Field("distance") distance: Float? = null,
        @Field("description") description: String? = null,
        @Field("trainer") trainer: Int? = null  // 1 for indoor/treadmill
    ): Response<StravaActivity>
}
