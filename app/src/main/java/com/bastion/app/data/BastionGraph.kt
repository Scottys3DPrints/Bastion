package com.bastion.app.data

import android.content.Context
import com.bastion.app.data.content.ContentRepository
import com.bastion.app.data.db.BastionDatabase
import com.bastion.app.data.prefs.SettingsStore
import com.bastion.app.data.repo.GuardRepository
import com.bastion.app.data.repo.GrowthRepository
import com.bastion.app.data.repo.JourneyRepository
import com.bastion.app.data.repo.SocialRepository

/**
 * Hand-rolled dependency graph.
 *
 * A full DI framework would earn its keep in a multi-module team codebase; here
 * it would only add build time and a layer of indirection over eight lazies.
 * Everything the UI touches goes through a repository — no screen ever sees a
 * DAO — which is what keeps a future sync backend a bolt-on.
 */
class BastionGraph(context: Context) {

    private val appContext = context.applicationContext

    /**
     * For work that must outlive the composable that started it — the guard
     * reconcile writes to DataStore and must not be cancelled by a rotation
     * halfway through recording that Guard is on.
     */
    val applicationScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )

    val database: BastionDatabase by lazy { BastionDatabase.build(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val content: ContentRepository by lazy { ContentRepository(appContext) }

    val journey: JourneyRepository by lazy {
        JourneyRepository(
            journeyDao = database.journeyDao(),
            habitDao = database.habitDao(),
            progressDao = database.progressDao(),
            settings = settings,
        )
    }

    val guard: GuardRepository by lazy {
        GuardRepository(
            guardDao = database.guardDao(),
            content = content,
            settings = settings,
        )
    }

    val growth: GrowthRepository by lazy {
        GrowthRepository(
            habitDao = database.habitDao(),
            progressDao = database.progressDao(),
            covenantDao = database.covenantDao(),
            content = content,
        )
    }

    val social: SocialRepository by lazy {
        SocialRepository(
            socialDao = database.socialDao(),
            content = content,
        )
    }

    companion object {
        fun from(context: Context): BastionGraph =
            (context.applicationContext as com.bastion.app.BastionApp).graph
    }
}
