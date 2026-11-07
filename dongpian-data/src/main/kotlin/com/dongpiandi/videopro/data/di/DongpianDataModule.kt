package com.dongpiandi.videopro.data.di

import com.dongpiandi.videopro.data.DongpianCatalogRepository
import com.dongpiandi.videopro.data.DongpianCatalogRepositoryImpl
import com.dongpiandi.videopro.data.DongpianEnvironment
import com.dongpiandi.videopro.data.DongpianLibraryRepository
import com.dongpiandi.videopro.data.DongpianLibraryRepositoryImpl
import com.dongpiandi.videopro.data.DongpianPlaybackRepository
import com.dongpiandi.videopro.data.DongpianPlaybackRepositoryImpl
import com.dongpiandi.videopro.data.DongpianSessionCookieAccess
import com.dongpiandi.videopro.data.DongpianSessionRepository
import com.dongpiandi.videopro.data.DongpianSessionRepositoryImpl
import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianSigningKeyProvider
import com.dongpiandi.videopro.data.transport.WebDongpianSigningKeyProvider
import dev.jellystack.core.security.SecureStoreFactory
import dev.jellystack.network.ClientConfig
import dev.jellystack.network.NetworkClientFactory
import org.koin.dsl.module

val dongpianDataModule =
    module {
        single { DongpianEnvironment() }
        single {
            NetworkClientFactory.create(
                ClientConfig(
                    maxRetries = 0,
                    installLogging = false,
                    userAgent = "VideoProTV/0.1",
                    configure = { followRedirects = false },
                ),
            )
        }
        single<DongpianSigningKeyProvider> {
            WebDongpianSigningKeyProvider(
                client = get(),
                environment = get(),
            )
        }
        single {
            DongpianApiClient(
                client = get(),
                environment = get(),
                signingKeyProvider = get(),
            )
        }
        single {
            DongpianSessionRepositoryImpl(
                apiClient = get(),
                secureStore = get<SecureStoreFactory>().create("dongpian-session"),
            )
        }
        single<DongpianSessionRepository> { get<DongpianSessionRepositoryImpl>() }
        single<DongpianSessionCookieAccess> { get<DongpianSessionRepositoryImpl>() }
        single<DongpianCatalogRepository> {
            DongpianCatalogRepositoryImpl(apiClient = get())
        }
        single<DongpianPlaybackRepository> {
            DongpianPlaybackRepositoryImpl(apiClient = get())
        }
        single<DongpianLibraryRepository> {
            DongpianLibraryRepositoryImpl(
                apiClient = get(),
                sessionCookieAccess = get(),
            )
        }
    }
