package gov.nist.csd.pm.epp.functions;

import gov.nist.csd.pm.epp.FunctionEvaluator;
import gov.nist.csd.pm.epp.events.EventContext;
import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.pdp.PDP;
import gov.nist.csd.pm.pdp.services.UserContext;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import gov.nist.csd.pm.pip.obligations.model.functions.Arg;
import gov.nist.csd.pm.pip.obligations.model.functions.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GetNodeExecutorTest {

    private TestUtil.TestContext testCtx;

    @BeforeEach
    void setUp() throws PMException {
        testCtx = TestUtil.getTestCtx();
    }

    @Test
    void TestExec() throws PMException {
        GetNodeExecutor executor = new GetNodeExecutor();

        EventContext eventContext = null;
        PDP pdp = testCtx.getPdp();
        Function function = new Function(executor.getFunctionName(),
                Arrays.asList(new Arg("oa1"), new Arg("OA")));

        UserContext superUser = new UserContext("super");
        Node node = executor.exec(pdp.withUser(superUser).getGraph(), pdp.withUser(superUser).getProhibitions(),
                pdp.withUser(superUser).getObligations(),
                eventContext, function, new FunctionEvaluator());

        assertNotNull(node);
        assertEquals(testCtx.getOa1(), node);
    }
}
