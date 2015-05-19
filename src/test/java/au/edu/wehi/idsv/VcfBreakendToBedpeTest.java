package au.edu.wehi.idsv;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import htsjdk.variant.variantcontext.writer.VariantContextWriter;

import org.junit.Test;

import com.google.common.base.Function;
import com.google.common.collect.Lists;


public class VcfBreakendToBedpeTest extends IntermediateFilesTest {
	private void create() {
		ProcessingContext pc = getCommandlineContext();
		VariantContextWriter vcw = pc.getVariantContextWriter(output, false);
		vcw.add(new IdsvVariantContextBuilder(pc, BP("ao", new BreakpointSummary(0, FWD, 1, 2, 3, BWD, 4, 5))).phredScore(6).make());
		vcw.add(new IdsvVariantContextBuilder(pc, BP("ah", new BreakpointSummary(3, BWD, 4, 5, 0, FWD, 1, 2))).phredScore(6).make());
		vcw.add(new IdsvVariantContextBuilder(pc, BP("bo", new BreakpointSummary(1, BWD, 10, 20, 2, FWD, 40, 50))).phredScore(100).make());
		vcw.add(new IdsvVariantContextBuilder(pc, BP("bh", new BreakpointSummary(2, FWD, 40, 50, 1, BWD, 10, 20))).phredScore(100).make());
		vcw.close();
	}
	private List<List<String>> parse() throws IOException {
		return Lists.transform(Files.readAllLines(bedpe().toPath()), new Function<String, List<String>>() {
			@Override
			public List<String> apply(String input) {
				return Lists.newArrayList(input.split("\\s"));
			}
		});
	}
	private File bedpe() { return new File(output.toString() + ".bedpe"); }
	@Test
	public void should_write_only_lower_breakend() throws IOException {
		create();
		VcfBreakendToBedpe cmd = new VcfBreakendToBedpe();
		cmd.INPUT = output;
		cmd.OUTPUT = bedpe();
		cmd.REFERENCE = getCommandlineContext().getReferenceFile();
		cmd.doWork();
		assertEquals(2, parse().size());
	}
	@Test
	public void should_write_chromosome_name() throws IOException {
		File bedpe = new File(output.toString() + ".bedpe");
		create();
		VcfBreakendToBedpe cmd = new VcfBreakendToBedpe();
		cmd.INPUT = output;
		cmd.OUTPUT = bedpe;
		cmd.REFERENCE = getCommandlineContext().getReferenceFile();
		cmd.doWork();
		List<List<String>> result = parse();
		assertEquals("polyA", result.get(0).get(0));
		assertEquals("Npower2", result.get(0).get(3));
		assertEquals("polyACGT", result.get(1).get(0));
		assertEquals("random", result.get(1).get(3));
	}
	@Test
	public void should_write_zero_based_coordinates() throws IOException {
		File bedpe = new File(output.toString() + ".bedpe");
		create();
		VcfBreakendToBedpe cmd = new VcfBreakendToBedpe();
		cmd.INPUT = output;
		cmd.OUTPUT = bedpe;
		cmd.REFERENCE = getCommandlineContext().getReferenceFile();
		cmd.doWork();
		List<List<String>> result = parse();
		assertEquals(1 - 1, Integer.parseInt(result.get(0).get(1)));
		assertEquals(2 - 1, Integer.parseInt(result.get(0).get(2)));
		assertEquals(4 - 1, Integer.parseInt(result.get(0).get(4)));
		assertEquals(5 - 1, Integer.parseInt(result.get(0).get(5)));
		assertEquals(10 - 1, Integer.parseInt(result.get(1).get(1)));
		assertEquals(20 - 1, Integer.parseInt(result.get(1).get(2)));
		assertEquals(40 - 1, Integer.parseInt(result.get(1).get(4)));
		assertEquals(50 - 1, Integer.parseInt(result.get(1).get(5)));
	}
	@Test
	public void should_write_qual_score() throws IOException {
		File bedpe = new File(output.toString() + ".bedpe");
		create();
		VcfBreakendToBedpe cmd = new VcfBreakendToBedpe();
		cmd.INPUT = output;
		cmd.OUTPUT = bedpe;
		cmd.REFERENCE = getCommandlineContext().getReferenceFile();
		cmd.doWork();
		List<List<String>> result = parse();
		assertEquals(6, Double.parseDouble(result.get(0).get(7)), 0);
		assertEquals(100, Double.parseDouble(result.get(1).get(7)), 0);
	}
	public void should_use_vcf_id_as_id() throws IOException {
		File bedpe = new File(output.toString() + ".bedpe");
		create();
		VcfBreakendToBedpe cmd = new VcfBreakendToBedpe();
		cmd.INPUT = output;
		cmd.OUTPUT = bedpe;
		cmd.REFERENCE = getCommandlineContext().getReferenceFile();
		cmd.doWork();
		List<List<String>> result = parse();
		assertEquals("ao", result.get(1).get(6));
		assertEquals("bo", result.get(2).get(6));
	}
	public void positive_strand_should_indicate_forward_breakend() throws IOException {
		File bedpe = new File(output.toString() + ".bedpe");
		create();
		VcfBreakendToBedpe cmd = new VcfBreakendToBedpe();
		cmd.INPUT = output;
		cmd.OUTPUT = bedpe;
		cmd.REFERENCE = getCommandlineContext().getReferenceFile();
		cmd.doWork();
		List<List<String>> result = parse();
		assertEquals("+", result.get(0).get(8));
		assertEquals("-", result.get(0).get(9));
		assertEquals("-", result.get(1).get(8));
		assertEquals("+", result.get(1).get(9));
	}
}
