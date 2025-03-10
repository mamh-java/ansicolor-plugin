package hudson.plugins.ansicolor;

import hudson.ExtensionList;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.ansicolor.mock.kubernetes.pipeline.SecretsMasker;
import hudson.plugins.ansicolor.mock.plugins.pipeline.maven.WithMavenStep;
import hudson.plugins.ansicolor.mock.timestamper.pipeline.GlobalDecorator;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.StringWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class AnsiColorStepTest {

    @Test
    void testPipelineStep(JenkinsRule jenkinsRule) throws Exception {
        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "ansiColor('xterm') {\n"
                + "  echo 'The following word is supposed to be \\u001B[31mred\\u001B[0m'\n"
                + " echo \"TERM=${env.TERM}\""
                + "}"
            , true));
        WorkflowRun run = jenkinsRule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        StringWriter writer = new StringWriter();
        assertTrue(p.getLastBuild().getLogText().writeHtmlTo(0L, writer) > 0);
        String html = writer.toString();
        jenkinsRule.assertLogContains("TERM=xterm", run);
        assertTrue(
            html.replaceAll("<!--.+?-->", "").matches("(?s).*<span style=\"color: #CD0000;\">red</span>.*"),
            "Failed to match color attribute in following HTML log output:\n" + html
        );
    }

    @Issue("180")
    @Test
    void canRenderMultiplePipelineSteps(JenkinsRule jenkinsRule) throws Exception {
        final String script = "echo '\033[32mbefore\033[0m'\n" +
            "ansiColor('vga') {\n" +
            "    echo '\033[32mstep one\033[0m'\n" +
            "}\n" +
            "echo '\033[32mbetween two steps\033[0m'\n" +
            "ansiColor('xterm') {\n" +
            "    echo '\033[32mstep two\033[0m'\n" +
            "}\n" +
            "echo '\033[32mafter step two\033[0m'\n" +
            "ansiColor {\n" +
            "    echo '\033[32mstep three\033[0m'\n" +
            "}\n" +
            "echo '\033[32mafter step three\033[0m'";

        assertOutputOnRunningPipeline(
            jenkinsRule,
            Arrays.asList(
                "\033[32mbefore\033[0m",
                "<span style=\"color: #00AA00;\">step one</span>",
                "\033[32mbetween two steps\033[0m",
                "<span style=\"color: #00CD00;\">step two</span>",
                "\033[32mafter step two\033[0m",
                "<span style=\"color: #00CD00;\">step three</span>",
                "\033[32mafter step three\033[0m"
            ),
            Arrays.asList(
                "<span style=\"color: #00AA00;\">before</span>",
                "\033[32mstep one\033[0m",
                "<span style=\"color: #00AA00;\">between two steps</span>",
                "\033[32mstep two\033[0m",
                "<span style=\"color: #00AA00;\">after step two</span>",
                "\033[32mstep three\033[0m",
                "<span style=\"color: #00AA00;\">after step three</span>"
            ),
            script
        );
    }

    @Issue("JENKINS-61598")
    @Test
    void willNotLeakFormattingToMetadataLines(JenkinsRule jenkinsRule) throws Exception {
        final String script = "ansiColor('xterm') {\n" +
            "    echo '\033[33mYellow words, white background.'\n" +
            "    echo '\033[35mMagenta words, white background.'\n" +
            "}";
        String nl = System.lineSeparator();
        assertOutputOnRunningPipeline(
            jenkinsRule,
            Arrays.asList(
                "<span style=\"color: #CDCD00;\">Yellow words, white background." + nl + "</span>",
                "[Pipeline] echo",
                "<span style=\"color: #CD00CD;\">Magenta words, white background." + nl + "</span>",
                "[Pipeline] }"
            ),
            Arrays.asList(
                "\033[33mYellow words, white background.",
                "<span style=\"color: #CDCD00;\">[Pipeline] echo",
                "\033[35mMagenta words, white background.",
                "<span style=\"color: #CD00CD;\">[Pipeline] }" + nl + "</span>"
            ),
            script
        );
    }

    @Disabled("TODO flaky test")
    @Issue("200")
    @Test
    void canRenderLongOutputWhileBuildStillRunning(JenkinsRule jenkinsRule) throws Exception {
        final String a1k = JenkinsTestSupport.repeat("a", 1024);
        final String script = "ansiColor('xterm') {\n" +
            "for (i = 0; i < 1000; i++) {" +
            "echo '\033[32m" + a1k + "\033[0m'\n" +
            "}" +
            "}";
        final WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "canRenderLongOutputWhileBuildStillRunning");
        project.setDefinition(new CpsFlowDefinition(script, true));
        QueueTaskFuture<WorkflowRun> runFuture = project.scheduleBuild2(0);
        assertNotNull(runFuture);
        final WorkflowRun lastBuild = runFuture.waitForStart();
        await().pollInterval(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(150)).until(() -> {
            StringWriter writer = new StringWriter();
            final int skipInitialStartAction = 3000;
            assertTrue(lastBuild.getLogText().writeHtmlTo(skipInitialStartAction, writer) > 0);
            final String html = writer.toString().replaceAll("<!--.+?-->", "");
            return !runFuture.isDone() && html.contains("<span style=\"color: #00CD00;\">" + a1k + "</span>") && !html.contains("\033[32m");
        });
    }

    @Test
    void willPrintAdditionalNlOnKubernetesPlugin(JenkinsRule jenkinsRule) throws Exception {
        ExtensionList.lookup(DynamicContext.Typed.class).add(0, new SecretsMasker());
        assertNlsOnRunningPipeline(jenkinsRule);
    }

    @Test
    void willPrintAdditionalNlOnTimestamperPlugin(JenkinsRule jenkinsRule) throws Exception {
        ExtensionList.lookup(TaskListenerDecorator.Factory.class).add(0, new GlobalDecorator());
        assertNlsOnRunningPipeline(jenkinsRule);
    }

    @Issue("222")
    @Test
    void willPrintAdditionalNlOnLogstashPlugin(JenkinsRule jenkinsRule) throws Exception {
        ExtensionList.lookup(TaskListenerDecorator.Factory.class).add(0, new hudson.plugins.ansicolor.mock.logstash.pipeline.GlobalDecorator());
        assertNlsOnRunningPipeline(jenkinsRule);
    }

    @Issue("223")
    @Test
    void willPrintAdditionalNlOnPipelineMavenPlugin(JenkinsRule jenkinsRule) throws Exception {
        ExtensionList.lookup(StepDescriptor.class).add(0, new WithMavenStep.DescriptorImpl());
        assertNlsOnRunningPipeline(jenkinsRule);
    }

    @Issue("218")
    @Test
    void canUseAndReportGlobalColorMapName(JenkinsRule jenkinsRule) throws Exception {
        final String globalColorMapName = "vga";
        final String script = "ansiColor {\n" +
            "    echo '\033[33mYellow words, white background.\033[0m'\n" +
            "    echo \"TERM=${env.TERM}\"\n" +
            "}";
        final List<String> expectedOutput = Arrays.asList(
            "<span style=\"color: #AA5500;\">Yellow words, white background.</span>",
            "TERM=" + globalColorMapName
        );
        final List<String> notExpectedOutput = Arrays.asList(
            "\033[33mYellow words, white background.",
            "TERM=null"
        );
        Jenkins.get().getDescriptorByType(AnsiColorBuildWrapper.DescriptorImpl.class).setGlobalColorMapName(globalColorMapName);

        final WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        project.setDefinition(new CpsFlowDefinition(script, true));
        jenkinsRule.assertBuildStatusSuccess(project.scheduleBuild2(0));
        StringWriter writer = new StringWriter();
        assertTrue(project.getLastBuild().getLogText().writeHtmlTo(0, writer) > 0);
        final String html = writer.toString().replaceAll("<!--.+?-->", "");
        for (String expected : expectedOutput) {
            assertThat(html, containsString(expected));
        }
        for (String notExpected : notExpectedOutput) {
            assertThat(html, not(containsString(notExpected)));
        }
    }

    @Issue("218")
    @Test
    void canUseAndReportDefaultColorMapName(JenkinsRule jenkinsRule) throws Exception {
        final String script = "ansiColor {\n" +
            "    echo '\033[33mYellow words, white background.\033[0m'\n" +
            "    echo \"TERM=${env.TERM}\"\n" +
            "}";
        assertOutputOnRunningPipeline(
            jenkinsRule,
            Arrays.asList(
                "<span style=\"color: #CDCD00;\">Yellow words, white background.</span>",
                "TERM=xterm"
            ),
            Arrays.asList(
                "\033[33mYellow words, white background.",
                "TERM=null"
            ),
            script
        );
    }

    @Issue("JENKINS-66684")
    @Test
    void canGetConstructorParametersForSnippetGenerator(JenkinsRule jenkinsRule) {
        final String colorMapName = AnsiColorMap.VGA.getName();
        final AnsiColorStep step = new AnsiColorStep(colorMapName);
        assertEquals(colorMapName, step.getColorMapName());
    }

    private void assertOutputOnRunningPipeline(JenkinsRule jenkinsRule, Collection<String> expectedOutput, Collection<String> notExpectedOutput, String pipelineScript) throws Exception {
        final WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        project.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        jenkinsRule.assertBuildStatusSuccess(project.scheduleBuild2(0));
        StringWriter writer = new StringWriter();
        assertTrue(project.getLastBuild().getLogText().writeHtmlTo(0, writer) > 0);
        final String html = writer.toString().replaceAll("<!--.+?-->", "");
        for (String expected : expectedOutput) {
            assertThat(html, containsString(expected));
        }
        for (String notExpected : notExpectedOutput) {
            assertThat(html, not(containsString(notExpected)));
        }
    }

    private void assertNlsOnRunningPipeline(JenkinsRule jenkinsRule) throws Exception {
        final String script = "ansiColor('xterm') {\n" +
            "echo '\033[34mHello\033[0m \033[33mcolorful\033[0m \033[35mworld!\033[0m'" +
            "}";
        final WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "willPrintAdditionalNlOnKubernetesPlugin");
        project.setDefinition(new CpsFlowDefinition(script, true));
        jenkinsRule.assertBuildStatusSuccess(project.scheduleBuild2(0));
        StringWriter writer = new StringWriter();
        assertTrue(project.getLastBuild().getLogText().writeHtmlTo(0, writer) > 0);
        final String html = writer.toString().replaceAll("<!--.+?-->", "")
            .replaceAll("</span>", "")
            .replaceAll("<span.+?>", "")
            .replaceAll("<div.+?/div>", "");
        final String nl = System.lineSeparator();
        assertThat(html, containsString("ansiColor" + nl + "[Pipeline] {" + nl + nl));
        assertThat(html, containsString("[Pipeline] }" + nl + nl + "[Pipeline] // ansiColor"));
    }
}
