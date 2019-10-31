package gov.nist.csd.pm.epp.functions;

import gov.nist.csd.pm.epp.FunctionEvaluator;
import gov.nist.csd.pm.epp.events.AssignToEvent;
import gov.nist.csd.pm.epp.events.EventContext;
import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.pdp.PDP;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import gov.nist.csd.pm.pip.obligations.model.functions.Arg;
import gov.nist.csd.pm.pip.obligations.model.functions.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GetNodeNameExecutorTest {

    private TestUtil.TestContext testCtx;

    @BeforeEach
    void setUp() throws PMException {
        testCtx = TestUtil.getTestCtx();
    }

    @Test
    void TestExec() throws PMException {
        GetNodeNameExecutor executor = new GetNodeNameExecutor();

        EventContext eventContext = new AssignToEvent(testCtx.getOa1(), testCtx.getO1());
        long user = testCtx.getU1().getID();
        long process = 1234;
        PDP pdp = testCtx.getPdp();
        Function function = new Function(executor.getFunctionName(),
                Arrays.asList(new Arg(new Function("get_node", Arrays.asList(new Arg("oa1"), new Arg("OA"))))));

        String name = executor.exec(eventContext, user, process, pdp, function, new FunctionEvaluator());

        assertNotNull(name);
        assertEquals("oa1", name);
    }
}