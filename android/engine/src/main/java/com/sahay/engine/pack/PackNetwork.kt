package com.sahay.engine.pack

import com.sahay.core.contracts.SahayConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** The part of the REST API (docs/CONTRACTS.md §3) that the pack repository needs. */
internal interface SahayApi {
    @GET("regions")
    suspend fun regions(): List<RegionDto>

    /** Raw body: the exact manifest text is saved to disk, then decoded with [PackNetwork.json]. */
    @GET("packs/{regionId}/manifest")
    suspend fun manifest(
        @Path("regionId") regionId: String,
        @Query("start") start: String,
        @Query("end") end: String,
    ): ResponseBody

    /** Absolute URL (GitHub release asset). Streamed, so the caller must close the body. */
    @Streaming
    @GET
    suspend fun download(@Url url: String): ResponseBody
}

/**
 * Retrofit/OkHttp setup. Deliberately NOT exposed to Hilt, so it cannot clash with an OkHttpClient another
 * module provides. Timeouts are per connect/read/write operation (no overall call timeout), so a big pack
 * download is fine as long as bytes keep arriving.
 */
internal class PackNetwork {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    val api: SahayApi by lazy {
        Retrofit.Builder()
            .baseUrl(SahayConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SahayApi::class.java)
    }
}
