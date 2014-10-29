package au.edu.wehi.idsv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.common.collect.Lists;

public class EvidenceClusterProcessorTest extends TestHelper {
	@Test
	public void singleton_should_call_both_breakends() {
		EvidenceClusterProcessor ecp = new EvidenceClusterProcessor(getContext());
		ecp.addEvidence(SCE(FWD, withSequence("TTTT", Read(0, 1, "1M3S"))[0], Read(1, 10, "3M")));
		List<VariantContextDirectedEvidence> result = Lists.newArrayList(ecp.iterator());
		assertEquals(2, result.size());
		assertTrue(result.get(0) instanceof VariantContextDirectedBreakpoint);
		BreakpointSummary bp = ((VariantContextDirectedBreakpoint)result.get(0)).getBreakendSummary();
		assertEquals(0, bp.referenceIndex);
		assertEquals(1, bp.start);
		assertEquals(1, bp.end);
		assertEquals(FWD, bp.direction);
		assertEquals(1, bp.referenceIndex2);
		assertEquals(10, bp.start2);
		assertEquals(10, bp.end2);
		assertEquals(BWD, bp.direction2);
		
		assertTrue(result.get(1) instanceof VariantContextDirectedBreakpoint);
		bp = ((VariantContextDirectedBreakpoint)result.get(1)).getBreakendSummary();
		assertEquals(0, bp.referenceIndex2);
		assertEquals(1, bp.start2);
		assertEquals(1, bp.end2);
		assertEquals(FWD, bp.direction2);
		assertEquals(1, bp.referenceIndex);
		assertEquals(10, bp.start);
		assertEquals(10, bp.end);
		assertEquals(BWD, bp.direction);
	}
}