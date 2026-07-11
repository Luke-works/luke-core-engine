package com.luke.engine.config;

import org.finos.fluxnova.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.finos.fluxnova.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wires JSR-223 scripting languages into the CIBSeven engine and makes the
 * GraalVM JS engine usable from BPMN script tasks.
 *
 * The language engines themselves (Groovy, GraalVM JS, Jython) are pulled in as
 * Maven dependencies and discovered automatically by the engine's
 * {@link ScriptEngineManager}. This plugin handles the engine-side
 * configuration that auto-discovery alone does NOT cover:
 *
 * <ul>
 *   <li><b>Host access</b> — GraalVM JS sandboxes scripts by default, which
 *       blocks access to Java host objects like {@code execution}. Camunda's
 *       {@code configureScriptEngineHostAccess} flag opens the access GraalVM JS
 *       needs to read/write process variables.</li>
 *   <li><b>Nashorn compatibility</b> — makes GraalVM JS behave like the old
 *       (removed) Nashorn engine, so existing {@code javascript} scripts keep
 *       working without rewrites.</li>
 *   <li><b>External resources</b> — left disabled so scripts cannot read files
 *       or URLs from the host.</li>
 * </ul>
 *
 * On boot it logs the script languages the engine can actually run, so the
 * available capabilities are visible in the startup logs.
 */
@Component
public class ScriptingEnginePlugin extends AbstractProcessEnginePlugin {

    private static final Logger log = LoggerFactory.getLogger(ScriptingEnginePlugin.class);

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        // Let GraalVM JS scripts interact with Java host objects (execution, etc.).
        configuration.setConfigureScriptEngineHostAccess(true);
        // Make GraalVM JS a drop-in for the removed Nashorn engine.
        configuration.setEnableScriptEngineNashornCompatibility(true);
        // Keep scripts from loading files/URLs off the host (security).
        configuration.setEnableScriptEngineLoadExternalResources(false);

        logAvailableEngines();
    }

    private void logAvailableEngines() {
        try {
            List<ScriptEngineFactory> factories = new ScriptEngineManager().getEngineFactories();
            if (factories.isEmpty()) {
                log.warn("No JSR-223 script engines found on the classpath");
                return;
            }
            String summary = factories.stream()
                    .map(f -> String.format("%s %s (names: %s)",
                            f.getEngineName(), f.getEngineVersion(), String.join(", ", f.getNames())))
                    .collect(Collectors.joining("; "));
            log.info("Scripting enabled — available engines: {}", summary);
        } catch (Exception e) {
            log.warn("Could not enumerate script engines: {}", e.getMessage());
        }
    }
}
