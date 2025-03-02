package io.quarkiverse.chappie.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.dev.ExceptionNotificationBuildItem;
import io.quarkus.deployment.logging.LoggingDecorateBuildItem;
import io.quarkus.devui.spi.buildtime.BuildTimeActionBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.runtime.logging.DecorateStackUtil;
import io.quarkus.vertx.http.deployment.ErrorPageActionsBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

@BuildSteps(onlyIf = IsDevelopment.class)
class ChappieDevUIProcessor {
    private static final String EXCEPTION_TITLE = "Help with the latest exception";

    static volatile Path srcMainJava;
    static volatile List<String> knownClasses;

    @BuildStep(onlyIf = ChappieEnabled.class)
    void createBroadcasters(BuildProducer<BroadcastsBuildItem> broadcastsProducer) {
        BroadcastProcessor<LastException> leb = BroadcastProcessor.create();
        broadcastsProducer.produce(new BroadcastsBuildItem(leb));
    }

    @Consume(ConsoleInstalledBuildItem.class)
    @BuildStep(onlyIf = ChappieEnabled.class)
    void setupBroadcaster(BuildProducer<ExceptionNotificationBuildItem> exceptionNotificationProducer,
            BroadcastsBuildItem broadcastsBuildItem,
            LastExceptionBuildItem lastExceptionBuildItem) {

        exceptionNotificationProducer
                .produce(new ExceptionNotificationBuildItem(new BiConsumer<Throwable, StackTraceElement>() {
                    @Override
                    public void accept(Throwable throwable, StackTraceElement stackTraceElement) {
                        String decorateString = DecorateStackUtil.getDecoratedString(throwable, srcMainJava, knownClasses);
                        LastException lastException = new LastException(stackTraceElement, throwable, decorateString);
                        broadcastsBuildItem.getLastExceptionBroadcastProcessor().onNext(lastException);
                        lastExceptionBuildItem.getLastException().set(lastException);
                    }
                }));
    }

    @BuildStep(onlyIf = ChappieEnabled.class)
    void addActionToErrorPage(BuildProducer<ErrorPageActionsBuildItem> errorPageActionsProducer,
            NonApplicationRootPathBuildItem nonApplicationRootPathBuildItem) {

        String url = nonApplicationRootPathBuildItem.resolvePath(
                "dev-ui/io.quarkiverse.chappie.quarkus-chappie/" + EXCEPTION_TITLE.replace(" ", "-").toLowerCase());
        errorPageActionsProducer.produce(new ErrorPageActionsBuildItem("Get help with this", url));

    }

    @BuildStep(onlyIfNot = ChappieEnabled.class)
    void notenabled(BuildProducer<CardPageBuildItem> cardPageProducer, ChappieConfig config) {
        CardPageBuildItem chappieCard = new CardPageBuildItem();

        chappieCard.addBuildTimeData("llm", config.llm());
        if (config.llm().equals(LLM.openai)) {
            chappieCard.addBuildTimeData("modelName", config.openai().modelName());
            chappieCard.addPage(Page.webComponentPageBuilder()
                    .icon("font-awesome-solid:circle-question")
                    .title("Configure assistant")
                    .componentLink("qwc-chappie-unconfigured.js"));
        }
        cardPageProducer.produce(chappieCard);
    }

    @BuildStep(onlyIf = ChappieEnabled.class)
    void pages(BuildProducer<CardPageBuildItem> cardPageProducer,
            ChappieConfig config) {
        CardPageBuildItem chappieCard = new CardPageBuildItem();
        chappieCard.setCustomCard("qwc-chappie-custom-card.js");

        chappieCard.addBuildTimeData("llm", config.llm());
        if (config.llm().equals(LLM.openai)) {
            chappieCard.addBuildTimeData("modelName", config.openai().modelName());
            chappieCard.addPage(Page.webComponentPageBuilder()
                    .icon("font-awesome-solid:circle-question")
                    .title(EXCEPTION_TITLE)
                    .componentLink("qwc-chappie-exception.js"));
        }
        cardPageProducer.produce(chappieCard);
    }

    @BuildStep(onlyIf = ChappieEnabled.class)
    void createBuildTimeActions(BuildProducer<BuildTimeActionBuildItem> buildTimeActionProducer,
            LoggingDecorateBuildItem loggingDecorateBuildItem,
            ChappieClientBuildItem chappieClientBuildItem,
            BroadcastsBuildItem broadcastsBuildItem,
            LastExceptionBuildItem lastExceptionBuildItem,
            LastSolutionBuildItem lastSolutionBuildItem,
            ChappieConfig chappieConfig) {

        if (srcMainJava == null) {
            srcMainJava = loggingDecorateBuildItem.getSrcMainJava();
        }
        if (knownClasses == null) {
            knownClasses = loggingDecorateBuildItem.getKnowClasses();
        }
        BuildTimeActionBuildItem buildItemActions = new BuildTimeActionBuildItem();

        // This gets the last know exception. For initial load.
        buildItemActions.addAction("getLastException", ignored -> {
            LastException lastException = lastExceptionBuildItem.getLastException().get();
            if (lastException != null) {
                return lastException;
            }
            return null;
        });

        // This streams exceptions as they happen
        buildItemActions.addSubscription("streamException", ignored -> {
            return broadcastsBuildItem.getLastExceptionPublisher();
        });

        // This suggest fix based on exception and code
        buildItemActions.addAction("suggestFix", ignored -> {
            LastException lastException = lastExceptionBuildItem.getLastException().get();
            if (lastException != null) {
                Path sourcePath = SourceCodeFinder.getSourceCodePath(srcMainJava, lastException.stackTraceElement());
                String sourceString = SourceCodeFinder.getSourceCode(srcMainJava, lastException.stackTraceElement());
                String stacktraceString = lastException.getStackTraceString();

                ChappieClient chappieClient = chappieClientBuildItem.getChappieClient();
                Object[] params = ParameterCreator.forExceptionHelp(stacktraceString, sourceString);
                CompletableFuture<Object> result = chappieClient.executeRPC("exception#suggestfix", params);

                result.thenApply(suggestedFix -> {
                    lastSolutionBuildItem.getLastSolution().set(suggestedFix);
                    lastSolutionBuildItem.getPath().set(sourcePath);
                    return suggestedFix;
                });

                return result;
            }
            return null;
        });

        // This suggest fix based on exception and code
        buildItemActions.addAction("applyFix", code -> {
            Object lastSolution = lastSolutionBuildItem.getLastSolution().get();
            Path path = lastSolutionBuildItem.getPath().get();
            if (lastSolution != null && path != null) {
                Map jsonRPCResponse = (Map) lastSolution;
                if (jsonRPCResponse.containsKey("suggestedSource")) {
                    String newCode = (String) jsonRPCResponse.get("suggestedSource");
                    try {
                        Files.writeString(path, newCode, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                        return path.toString();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
            }
            return null;
        });

        buildTimeActionProducer.produce(buildItemActions);
    }
}
