package net.primal.android.pcoc.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import net.primal.android.pcoc.admit.AdmissionSiteClient
import net.primal.android.pcoc.admit.newAdmissionHttpClient
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.android.pcoc.enroll.FileEnrolledPcocStore
import net.primal.android.pcoc.hike.LiveIndex

@Module
@InstallIn(SingletonComponent::class)
object PcocModule {

    @Provides
    @Singleton
    fun enrolledPcocStore(@ApplicationContext context: Context): EnrolledPcocStore =
        FileEnrolledPcocStore(directory = File(context.filesDir, OBJECT_DIRECTORY))

    @Provides
    @Singleton
    fun admissionSiteClient(): AdmissionSiteClient = AdmissionSiteClient(http = newAdmissionHttpClient())

    @Provides
    @Singleton
    fun liveIndex(): LiveIndex = LiveIndex(LiveIndex.newClient())

    private const val OBJECT_DIRECTORY = "pcoc-enrolled-objects"
}
