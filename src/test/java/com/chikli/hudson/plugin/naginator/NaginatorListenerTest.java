package com.chikli.hudson.plugin.naginator;

import java.io.IOException;

import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.SleepBuilder;

import com.chikli.hudson.plugin.naginator.testutils.MyBuilder;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.tasks.BuildTrigger;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Publisher;

public class NaginatorListenerTest extends HudsonTestCase {
    @Override
    protected void tearDown() throws Exception {
        try {
            super.tearDown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void testSuccessNoRebuild() throws Exception {
        assertEquals(false, isScheduledForRetry("build log", Result.SUCCESS, "foo", false, false));
    }

    public void testUnstableNoRebuild() throws Exception {
        assertEquals(false, isScheduledForRetry("build log", Result.SUCCESS, "foo", false, false));
    }

    public void testUnstableWithRebuild() throws Exception {
        assertEquals(true, isScheduledForRetry("build log", Result.UNSTABLE, "foo", true, false));
    }

    public void testFailureWithRebuild() throws Exception {
        assertEquals(true, isScheduledForRetry("build log", Result.FAILURE, "foo", false, false));
    }

    public void testFailureWithUnstableRebuild() throws Exception {
        assertEquals(true, isScheduledForRetry("build log", Result.FAILURE, "foo", true, false));
    }

    public void testFailureWithoutRebuildRegexp() throws Exception {
        assertEquals(false, isScheduledForRetry("build log", Result.FAILURE, "foo", false, true));
    }

    public void testFailureWithRebuildRegexp() throws Exception {
        assertEquals(true, isScheduledForRetry("build log foo", Result.FAILURE, "foo", false, true));
    }

    public void testUnstableWithoutRebuildRegexp() throws Exception {
        assertEquals(false, isScheduledForRetry("build log", Result.UNSTABLE, "foo", true, true));
    }

    public void testUnstableWithRebuildRegexp() throws Exception {
        assertEquals(true, isScheduledForRetry("build log foo", Result.UNSTABLE, "foo", true, true));
    }

    public void testWithBuildWrapper() throws Exception {

        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MyBuilder("foo", Result.SUCCESS, 1000));
        NaginatorPublisher nag = new NaginatorPublisher("foo", false, false, false, 10, new FixedDelay(0));
        project.getPublishersList().add(nag);
        BuildWrapper failTheBuild = new FailTheBuild();
        project.getBuildWrappersList().add(failTheBuild);

        assertEquals(true, isScheduledForRetry(project));
    }

    /**
     * A -> B
     *
     * A triggers B if successful.
     * B will be rebuild 2 times if it fails and it will.
     *
     * Test will check that B:s rebuild attempts will also contain the UpstreamCause from the original B:s build
     *
     * @throws Exception
     */
    public void testRetainCauses() throws Exception {
        FreeStyleProject a = createFreeStyleProject("a");
        FreeStyleProject b = createFreeStyleProject("b");
        a.getPublishersList().add(new BuildTrigger("b", Result.SUCCESS));
        NaginatorPublisher nag = new NaginatorPublisher("", false, false, false, 2, new FixedDelay(1));

        b.getPublishersList().add(nag);

        BuildWrapper failTheBuild = new FailTheBuild();
        b.getBuildWrappersList().add(failTheBuild);
        jenkins.rebuildDependencyGraph();

        buildAndAssertSuccess(a);
        waitUntilNoActivity();
        assertNotNull(a.getLastBuild());
        assertNotNull(b.getLastBuild());
        assertEquals(1, a.getLastBuild().getNumber());
        assertEquals(3, b.getLastBuild().getNumber());
        assertEquals(Result.FAILURE, b.getLastBuild().getResult());
        assertEquals(2, b.getBuildByNumber(2).getActions(CauseAction.class).size());
        assertEquals(2, b.getBuildByNumber(3).getActions(CauseAction.class).size());

    }


    private boolean isScheduledForRetry(String buildLog, Result result, String regexpForRerun,
                                    boolean rerunIfUnstable, boolean checkRegexp) throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new MyBuilder(buildLog, result));
        Publisher nag = new NaginatorPublisher(regexpForRerun, rerunIfUnstable, false, checkRegexp, 10, new FixedDelay(0));
        project.getPublishersList().add(nag);

        return isScheduledForRetry(project);
    }

    private boolean isScheduledForRetry(FreeStyleProject project) throws Exception {
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        waitUntilNoActivity();

        return project.getLastBuild().getNumber() > 1;
    }

    private static final class FailTheBuild extends BuildWrapper {
        @Override
        public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
            return new Environment() {
                @Override
                public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                    build.setResult(Result.FAILURE);
                    return true;
                }
            };
        }

        @Extension
        public static class DescriptorImpl extends BuildWrapperDescriptor {

            public boolean isApplicable(AbstractProject<?, ?> item) {
                return true;
            }

            public String getDisplayName() {
                return null;
            }
        }
    }
    
    @Bug(17626)
    public void testCountScheduleIndependently() throws Exception {
        // Running a two sequence of builds
        // with parameter PARAM=A and PARAM=B.
        // Each of them should be rescheduled
        // 2 times.
        
        FreeStyleProject p = createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("PARAM", "")
        ));
        p.getBuildersList().add(new SleepBuilder(100));
        p.getBuildersList().add(new FailureBuilder());
        p.getPublishersList().add(new NaginatorPublisher(
                "",                     // regexpForRerun
                false,                  // rerunIfUnstable
                false,                  // rerunMatrixPart
                false,                  // checkRegexp
                2,                      // maxSchedule
                new FixedDelay(0)       // delay
        ));
        
        p.scheduleBuild2(
                0,
                new Cause.UserIdCause(),
                new ParametersAction(
                    new StringParameterValue("PARAM", "A")
                )
        );
        p.scheduleBuild2(
                0,
                new Cause.UserIdCause(),
                new ParametersAction(
                    new StringParameterValue("PARAM", "B")
                )
        );
        
        waitUntilNoActivity();
        
        assertEquals(3, Collections2.filter(
                p.getBuilds(),
                new Predicate<FreeStyleBuild>() {
                    public boolean apply(FreeStyleBuild b) {
                        try {
                            return "A".equals(b.getEnvironment(TaskListener.NULL).get("PARAM"));
                        } catch (IOException e) {
                            return false;
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }
        ).size());
        assertEquals(3, Collections2.filter(
                p.getBuilds(),
                new Predicate<FreeStyleBuild>() {
                    public boolean apply(FreeStyleBuild b) {
                        try {
                            return "B".equals(b.getEnvironment(TaskListener.NULL).get("PARAM"));
                        } catch (IOException e) {
                            return false;
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }
        ).size());
    }
    
    @Bug(24903)
    public void testCatastorophicRegularExpression() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        p.getBuildersList().add(new MyBuilder("0000000000000000000000000000000000000000000000000000", Result.FAILURE));
        p.getPublishersList().add(new NaginatorPublisher(
                "(0*)*NOSUCHSTRING",               // regexpForRerun
                false,                  // rerunIfUnstable
                false,                  // rerunMatrixPart
                true,                   // checkRegexp
                1,                      // maxSchedule
                new FixedDelay(0)       // delay
        ));
        
        ((NaginatorPublisher.DescriptorImpl)jenkins.getDescriptor(NaginatorPublisher.class))
            .setRegexpTimeoutMs(1000);
        
        p.scheduleBuild2(0);
        waitUntilNoActivityUpTo(10 * 1000);
    }
}
