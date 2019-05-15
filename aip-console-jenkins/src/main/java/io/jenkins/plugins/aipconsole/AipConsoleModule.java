package io.jenkins.plugins.aipconsole;

import com.castsoftware.uc.aip.console.tools.core.services.ChunkedUploadService;
import com.castsoftware.uc.aip.console.tools.core.services.ChunkedUploadServiceImpl;
import com.castsoftware.uc.aip.console.tools.core.services.JobsService;
import com.castsoftware.uc.aip.console.tools.core.services.JobsServiceImpl;
import com.castsoftware.uc.aip.console.tools.core.services.RestApiService;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import hudson.Extension;
import io.jenkins.plugins.aipconsole.extensions.RestApiServiceImpl;

@Extension
public class AipConsoleModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(RestApiService.class).toInstance(new RestApiServiceImpl());
    }

    @Provides
    @Singleton
    public JobsService getJobsService(RestApiService restApiService) {
        return new JobsServiceImpl(restApiService);
    }

    @Provides
    @Singleton
    public ChunkedUploadService getChunkedUploadService(RestApiService restApiService) {
        return new ChunkedUploadServiceImpl(restApiService);
    }
}
