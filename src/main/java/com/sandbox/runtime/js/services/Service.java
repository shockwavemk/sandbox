package com.sandbox.runtime.js.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.runtime.js.converters.NashornConverter;
import com.sandbox.runtime.js.models.Console;
import com.sandbox.runtime.js.models.ISandboxDefineCallback;
import com.sandbox.runtime.js.models.JSError;
import com.sandbox.runtime.js.models.JsonNode;
import com.sandbox.runtime.js.models.SandboxScriptObject;
import com.sandbox.runtime.js.utils.ErrorUtils;
import com.sandbox.runtime.js.utils.FileUtils;
import com.sandbox.runtime.js.utils.NashornUtils;
import com.sandbox.runtime.models.Cache;
import com.sandbox.runtime.models.Error;
import com.sandbox.runtime.models.HTTPRequest;
import com.sandbox.runtime.models.HTTPResponse;
import com.sandbox.runtime.models.HttpRuntimeResponse;
import com.sandbox.runtime.models.RoutingTable;
import com.sandbox.runtime.models.SandboxScriptEngine;
import com.sandbox.runtime.models.ServiceScriptException;
import com.sandbox.runtime.models.SuppressedServiceScriptException;
import com.sandbox.runtime.services.LiquidRenderer;
import jdk.nashorn.api.scripting.NashornException;
import jdk.nashorn.internal.runtime.ScriptObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by nickhoughton on 20/10/2014.
 */
public abstract class Service {
    final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final SandboxScriptEngine sandboxScriptEngine;
    protected String sandboxId;
    protected String fullSandboxId;
    HTTPRequest req;
    HTTPResponse res;
    SandboxScriptObject scriptObject = new SandboxScriptObject();
    NashornUtils nashornUtils;
    private boolean initialized = false;

    @Autowired
    protected Cache cache;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected Environment environment;

    @Autowired
    LiquidRenderer liquidRenderer;

    @Autowired
    ErrorUtils errorUtils;

    public Service(SandboxScriptEngine sandboxScriptEngine, NashornUtils nashornUtils, String fullSandboxId, String sandboxId) {
        this.sandboxScriptEngine = sandboxScriptEngine;
        this.fullSandboxId = fullSandboxId;
        this.sandboxId = sandboxId;
        this.nashornUtils = nashornUtils;
    }

    public SandboxScriptEngine getSandboxScriptEngine() {
        return sandboxScriptEngine;
    }

    public Console getConsole(){
        return sandboxScriptEngine.getConsole();
    }

    public NashornUtils getNashornUtils() {
        return nashornUtils;
    }

    public void initialize() throws Exception {
        if(initialized) return;
        initialized = true;

        loadContext();
        setState();
        loadService();
    }

    public HttpRuntimeResponse handleRequest(HTTPRequest req) {
        this.req = req;
        this.res = new HTTPResponse();

        try {
            initialize();
            setState();
            runService();
            HttpRuntimeResponse result = postProcessContext();

            return result;

        } catch (Exception e) {

            Error error = new Error();

            if (e instanceof IllegalArgumentException) {
                error = errorUtils.extractError(e);

            } else if (e instanceof ServiceScriptException) {
                error = errorUtils.extractError(e);

            } else if (e instanceof RuntimeException) {
                error.setDisplayMessage("There was a problem handling your request. Please try again in a minute");

            } else {
                error.setDisplayMessage("We encountered a system error. Please try again shortly");
            }

            //if not suppressed exception then log
            if(!(e instanceof SuppressedServiceScriptException))
                logger.info("Exception handling the request: " + e.getMessage(), e);

            return new HttpRuntimeResponse(error);

        }

    }

    public RoutingTable handleRoutingTableRequest() throws Exception {

        try {
            initialize();

            RoutingTable routingTable = new RoutingTable();
            routingTable.setRepositoryId(fullSandboxId);
            routingTable.setRouteDetails(scriptObject.getRoutes());

            return routingTable;

        } catch (Exception ex) {
            logger.error("Engine: " + sandboxScriptEngine.hashCode() + " - Exception handling routing table request", ex);
            // extract the JS error
            throw ex;
        }
    }

    public boolean handleFileChangeRequest(byte[] zipData) throws Exception {
        throw new RuntimeException("Not implemented");
    }


    //lower level steps
    protected void loadContext() throws Exception {
        // bootstrap the context with minimal environment
        setInScope("__mock", scriptObject, sandboxScriptEngine);
        setInScope("nashornUtils", getNashornUtils(), sandboxScriptEngine);
    }

    protected void loadEmptyState() throws Exception{
        setInScope("state", NashornConverter.instance().convert(sandboxScriptEngine.getEngine(), new JsonNode("{}").getJsonObject()), sandboxScriptEngine);
    }

    protected abstract void setState() throws Exception;

    protected abstract void saveState(Object state) throws Exception;

    //load service checks the main file exists and injects/evals it in the context, doesnt trigger the callback tho
    protected void loadService() throws Exception {
        // get it from cache, throw if not found
        String mainjs = getNashornUtils().readFile("main.js");
        if (mainjs == null || mainjs.isEmpty()) {
            // throw an exception
            throw new ServiceScriptException("Application is missing main.js (or its empty) - please add this file and commit");
        }

        try {
            //when we eval in the user code, clear the require cache first so the other JS files get recompiled, otherwise they won't get reload. change now we aren't clearing the context everytime potentially.
            evalScript("main", mainjs, sandboxScriptEngine);


        } catch (NashornException ne) {
            throw new ServiceScriptException(ne, ne.getFileName(), ne.getLineNumber(), ne.getColumnNumber());

        } catch (javax.script.ScriptException e) {
            throw new ServiceScriptException(e.getMessage());

        }

    }

    //run service triggers the route callback, mainjs file should already be loaded
    private void runService() throws Exception {

        try {
            //now script has fully evaled, run the matched function otherwise it might not have loaded stuff at the bottom of the file
            ISandboxDefineCallback matchedFunction = scriptObject.getMatchedFunction(req);
            if (matchedFunction != null) {
                setInScope("_matchedFunction", matchedFunction, sandboxScriptEngine);
                setInScope("_currentRequest", req, sandboxScriptEngine);
                setInScope("_currentResponse", res, sandboxScriptEngine);
                evalScript("sandbox-execute", "_matchedFunction.run(_currentRequest, _currentResponse)", sandboxScriptEngine);

            }else{
                throw new SuppressedServiceScriptException("Could not find a route definition matching your requested route " + req.getMethod() + " " + req.getPath());
            }

        } catch (NashornException ne) {
            throw new ServiceScriptException(ne, ne.getFileName(), ne.getLineNumber(), ne.getColumnNumber());

        } catch (ScriptException ne) {
            throw new ServiceScriptException(ne, ne.getFileName(), ne.getLineNumber(), ne.getColumnNumber());

        }

    }

    //after callback execution, get state/response/template etc and process
    private HttpRuntimeResponse postProcessContext() throws Exception {
        // save state
        Object convertedState = sandboxScriptEngine.getContext().getAttribute("state");
        saveState(convertedState);

        String _body = null;

        // process the response body and build the InstanceHttpResponse
        if (res.wasRendered()) {

            Assert.hasText(res.getTemplateName(), "Invalid template name given");

            // get template from cache
            String template = cache.getRepositoryFile(fullSandboxId, "templates/" + res.getTemplateName() + ".liquid");

            if (template == null) {
                throw new ServiceScriptException(String.format("Cannot find template with name '%1$s'", res.getTemplateName()));
            }

            Map templateLocals = res.getTemplateLocals();

            //allow unrendered templates to be passed, special param to support edge cases
            if(templateLocals != null && templateLocals.get("_passUnrenderedTemplate") != null){
                _body = template;
            }else{
                liquidRenderer.prepareValues(templateLocals);

                Map<String, Object> locals = new HashMap<String, Object>();
                locals.put("res", templateLocals);
                locals.put("req", req);
                locals.put("data", templateLocals);
                locals.put("__nashornUtils", getNashornUtils());

                try {
                    _body = liquidRenderer.render(template, locals);

                } catch (Exception e) {
                    //if we get a liquid runtime exception, from our custom tags, then rethrow as a script exception so it gets logged.
                    throw new ServiceScriptException(e.getMessage());
                }
            }

        } else if (res.getBody() == null) {
            throw new ServiceScriptException("No body has been set in route, you must call one of .json(), .send(), .render() etc");

        } else {
            if (res.getBody() instanceof ScriptObject || res.getBody() instanceof Map || res.getBody() instanceof Collection || res.getBody() instanceof
            JSError) {
                // convert JS object to JSON string
                _body = mapper.writeValueAsString(res.getBody());

            } else {
                // treat everything else as plain text
                _body = res.getBody().toString();
            }
        }

        // check for a status code being set
        // if an exception is thrown above, the Proxy will see the error at its end
        // and replace the status code with 500
        if (res.getStatus() == null) {
            res.status(200);
        }

        return new HttpRuntimeResponse(_body, res.getStatus(), res.getHeaders(), res.getCookies());
    }

    protected void setInScope(String name, Object value, SandboxScriptEngine sandboxScriptEngine){
        sandboxScriptEngine.getContext().setAttribute(
                name,
                value,
                ScriptContext.ENGINE_SCOPE
        );
    }

    protected void removeFromScope(String name, SandboxScriptEngine sandboxScriptEngine){
        sandboxScriptEngine.getContext().removeAttribute(
                name,
                ScriptContext.ENGINE_SCOPE
        );
    }

    private void evalScript(String name, SandboxScriptEngine sandboxScriptEngine) throws ScriptException {
        evalScript(name, FileUtils.loadJSFromResource(name), sandboxScriptEngine);
    }

    private void evalScript(String name, String scriptData, SandboxScriptEngine sandboxScriptEngine) throws ScriptException {
        sandboxScriptEngine.getContext().setAttribute(ScriptEngine.FILENAME, name+".js", ScriptContext.ENGINE_SCOPE);
        sandboxScriptEngine.getEngine().eval(scriptData, sandboxScriptEngine.getContext());
    }

}
