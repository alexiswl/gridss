package au.edu.wehi.idsv;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import htsjdk.samtools.BAMRecordCodec;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordCoordinateComparator;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.fastq.FastqReader;
import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.metrics.Header;
import htsjdk.samtools.metrics.StringHeader;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFileReader;

/**
 * Helper class for testing command-line programs
 * @author Daniel Cameron
 *
 */
public class IntermediateFilesTest extends TestHelper {
	@Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
	public File input;
	public File output;
	public File reference;
	@Before
	public void setup() throws IOException {
		reference = SMALL_FA_FILE;
		input = testFolder.newFile("input.bam");
		input.delete();
		output = testFolder.newFile("out.vcf");
		output.delete();
	}
	public void setReference(File ref) {
		reference = ref;
	}
	public ProcessingContext getCommandlineContext(boolean perChr) {
		List<Header> headers = Lists.newArrayList();
		headers.add(new StringHeader("TestHeader"));
		ProcessingContext pc;
		if (reference.equals(SMALL_FA_FILE)) {
			pc = new ProcessingContext(
				new FileSystemContext(testFolder.getRoot(), 500000), reference, perChr, SMALL_FA,
				headers, getConfig(testFolder.getRoot()));
		} else {
			pc = new ProcessingContext(
					new FileSystemContext(testFolder.getRoot(), 500000), reference, perChr, null, 
					headers, getConfig(testFolder.getRoot()));
		}
		pc.registerCategory(0, "Normal");
		pc.registerCategory(1, "Tumour");
		return pc;
	}
	public ProcessingContext getCommandlineContext() {
		return getCommandlineContext(false);
	}
	@After
	public void cleanup() throws Exception {
		input.delete();
		testFolder.delete(); // why isn't @Rule working?
	}
	public SAMRecord validSC(String seq, int referenceIndex, int position, String cigar) {
		SAMRecord r = withSequence(seq, Read(referenceIndex, position, cigar))[0];
		r.setMappingQuality(15);
		return r;
	}
	public SAMRecord ValidSC() {
		SAMRecord r = Read(0, 1, "25S50M25S");
		r.setReadName("SC1");
		byte[] qual = new byte[100];
		for (int i = 0; i < qual.length; i++) {
			qual[i] = 5;
		}
		r.setBaseQualities(qual);
		r.setMappingQuality(15);
		return r;
	}
	public void createInput(SAMRecord[] first, SAMRecord[]... data) {
		List<SAMRecord> list = Lists.newArrayList(first);
		for (SAMRecord[] array : data) {
			for (SAMRecord r : array) {
				list.add(r);
			}
		}
		createInput(list.toArray(new SAMRecord[0]));
	}
	public void createInput(File file) throws IOException {
		if (!file.exists()) throw new IllegalArgumentException(String.format("Missing %s", file));
		Files.copy(file, input);
	}
	public void createInput(SAMRecord... data) {
		createBAM(input, SortOrder.coordinate, data);
	}
	public void createInput(List<SAMRecord> data) {
		createInput(data.toArray(new SAMRecord[data.size()]));
	}
	public void createBAM(File file, SortOrder sortOrder, List<SAMRecord> data) {
		createBAM(file, sortOrder, data.toArray(new SAMRecord[data.size()]));
	}
	public void createBAM(File file, SortOrder sortOrder, SAMRecord... data) {
		SAMFileHeader header = getHeader();
		header.setSortOrder(sortOrder);
		SAMFileWriter writer =  new SAMFileWriterFactory().setCreateIndex(true).makeSAMOrBAMWriter(header, true, file);
		if (sortOrder == SortOrder.coordinate) {
			SortingCollection<SAMRecord> presort = SortingCollection.newInstance(SAMRecord.class, new BAMRecordCodec(header), new SAMRecordCoordinateComparator(), 100000000, testFolder.getRoot());
			for (SAMRecord r : data) {
				presort.add(r);
			}
			presort.doneAdding();
			for (SAMRecord r : presort) {
				writer.addAlignment(r);
			}
		} else {
			for (SAMRecord r : data) {
				writer.addAlignment(r);
			}
		}
		writer.close();
	}
	public void createVCF(GenomicProcessingContext context, File file, VariantContext... data) {
		VariantContextWriter writer = context.getVariantContextWriter(file, true);
		for (VariantContext vc : data) {
			writer.add(vc);
		}
		writer.close();
	}
	public void writeRealignment(GenomicProcessingContext pc, EvidenceSource source, int index, SAMRecord... records) {
		createBAM(pc.getFileSystemContext().getRealignmentBam(source.getFileIntermediateDirectoryBasedOn(), index), SortOrder.coordinate, records);
	}
	public void writeUnmappedRealignments(GenomicProcessingContext pc, AssemblyEvidenceSource source, int index) {
		writeRealignment(pc, source, 0, Lists.newArrayList(source.iterator(false, true)).stream().map(e -> {
			SAMRecord r = new SAMRecord(pc.getBasicSamHeader());
			r.setReadName(BreakpointFastqEncoding.getRealignmentFastq(e).getReadHeader());
			return r;
			}).collect(Collectors.toList()).toArray(new SAMRecord[0]));
	}
	public void writeUnmappedRealignments(GenomicProcessingContext pc, SAMEvidenceSource source, int index) {
		writeRealignment(pc, source, 0, Lists.newArrayList(source.iterator(false, true, false)).stream().map(e -> {
			SoftClipEvidence sc = (SoftClipEvidence)e;
			SAMRecord r = new SAMRecord(pc.getBasicSamHeader());
			r.setReadName(BreakpointFastqEncoding.getRealignmentFastq(sc).getReadHeader());
			return r;
			}).collect(Collectors.toList()).toArray(new SAMRecord[0]));
	}
	public static List<SAMRecord> getRecords(File file) {
		assertTrue(file.exists());
		SamReader reader = SamReaderFactory.make().open(file);
		List<SAMRecord> list = Lists.newArrayList();
		for (SAMRecord r : reader) {
			list.add(r);
		}
		try {
			reader.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return list;
	}
	public List<SAMRecord> getRecords(String extension) {
		File file = new File(input.getAbsolutePath() + ".idsv.working", input.getName() + extension);
		return getRecords(file);
	}
	public List<VariantContextDirectedEvidence> breaks(List<IdsvVariantContext> vcf) {
		List<VariantContextDirectedEvidence> list = Lists.newArrayList();
		for (IdsvVariantContext vc : vcf) {
			if (vc instanceof VariantContextDirectedEvidence) {
				list.add((VariantContextDirectedEvidence)vc);
			}
		}
		return list;
	}
	public List<VariantContextDirectedEvidence> breakpoints(final List<IdsvVariantContext> vcf) {
		List<VariantContextDirectedEvidence> list = Lists.newArrayList();
		for (IdsvVariantContext vc : vcf) {
			if (vc instanceof VariantContextDirectedEvidence) {
				VariantContextDirectedEvidence be = (VariantContextDirectedEvidence)vc;
				if (be.getBreakendSummary() instanceof BreakpointSummary) {
					list.add(be);
				}
			}
		}
		return list;
	}
	public List<IdsvVariantContext> getVcf(final File file, final EvidenceSource source) {
		assertTrue(file.exists());
		VCFFileReader reader = new VCFFileReader(file, false);
		List<IdsvVariantContext> list = Lists.newArrayList();
		for (VariantContext r : reader) {
			list.add(IdsvVariantContext.create(getCommandlineContext(), source == null ? AES() : source, r));
		}
		reader.close();
		return list;
	}
	public List<FastqRecord> getFastqRecords(File file) {
		List<FastqRecord> list = Lists.newArrayList();
		assertTrue(file.exists());
		FastqReader reader = new FastqReader(file);
		for (FastqRecord r : reader) {
			list.add(r);
		}
		reader.close();
		return list;
	}
	public List<SAMRecord> getAssembly(final AssemblyEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getAssembly(source.getFileIntermediateDirectoryBasedOn()));
	}
	public List<SAMRecord> getAssemblyRaw(final AssemblyEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getAssemblyRawBam(source.getFileIntermediateDirectoryBasedOn()));
	}
	public List<FastqRecord> getFastqRecords(final EvidenceSource source) {
		return getFastqRecords(getCommandlineContext().getFileSystemContext().getRealignmentFastq(source.getFileIntermediateDirectoryBasedOn(), 0));
	}
	public List<SAMRecord> getRP(final SAMEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getReadPairBam(source.getFileIntermediateDirectoryBasedOn()));
	}
	public List<SAMRecord> getSC(final SAMEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getSoftClipBam(source.getFileIntermediateDirectoryBasedOn()));
	}
	public List<SAMRecord> getRRR(final SAMEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getRealignmentRemoteBam(source.getFileIntermediateDirectoryBasedOn()));
	}
	public List<SAMRecord> getRSC(final SAMEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getSoftClipRemoteBam(source.getFileIntermediateDirectoryBasedOn()));
	}
	public List<IdsvVariantContext> getRA(final AssemblyEvidenceSource source) {
		return getVcf(getCommandlineContext().getFileSystemContext().getAssembly(source.getFileIntermediateDirectoryBasedOn()), source);
	}
	public List<SAMRecord> getMate(final SAMEvidenceSource source) {
		return getRecords(getCommandlineContext().getFileSystemContext().getMateBam(source.getFileIntermediateDirectoryBasedOn()));
	}
	public class PerChr {
		private final ProcessingContext context;
		public PerChr(ProcessingContext context) {
			this.context = context;
		}
		public List<SAMRecord> getAssembly(final AssemblyEvidenceSource source, String chr) {
			return getRecords(context.getFileSystemContext().getAssemblyForChr(source.getFileIntermediateDirectoryBasedOn(), chr));
		}
		public List<SAMRecord> getAssemblyRaw(final AssemblyEvidenceSource source, String chr) {
			return getRecords(context.getFileSystemContext().getAssemblyRawBamForChr(source.getFileIntermediateDirectoryBasedOn(), chr));
		}
		public List<SAMRecord> getAssembly(final AssemblyEvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<SAMRecord>>() {
				@Override
				public List<SAMRecord> apply(SAMSequenceRecord arg0) {
					return getRecords(context.getFileSystemContext().getAssemblyForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName()));
				}
			})));
		}
		public List<FastqRecord> getFastqRecords(final EvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<FastqRecord>>() {
				@Override
				public Iterable<FastqRecord> apply(SAMSequenceRecord arg0) {
					return IntermediateFilesTest.this.getFastqRecords(context.getFileSystemContext().getRealignmentFastqForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName(), 0));
				}
			})));
		}
		public List<FastqRecord> getFastqRecords(final EvidenceSource source, final String chr) {
			return IntermediateFilesTest.this.getFastqRecords(context.getFileSystemContext().getRealignmentFastqForChr(source.getFileIntermediateDirectoryBasedOn(), chr, 0));
		}
		public List<SAMRecord> getRP(final SAMEvidenceSource source, String chr) {
			return getRecords(context.getFileSystemContext().getReadPairBamForChr(source.getFileIntermediateDirectoryBasedOn(), chr));
		}
		public List<SAMRecord> getRP(final SAMEvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<SAMRecord>>() {
				@Override
				public Iterable<SAMRecord> apply(SAMSequenceRecord arg0) {
					return getRecords(context.getFileSystemContext().getReadPairBamForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName()));
				}
			})));
		}
		public List<SAMRecord> getSC(final SAMEvidenceSource source, String chr) {
			return getRecords(context.getFileSystemContext().getSoftClipBamForChr(source.getFileIntermediateDirectoryBasedOn(), chr));
		}
		public List<SAMRecord> getSC(final SAMEvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<SAMRecord>>() {
				@Override
				public Iterable<SAMRecord> apply(SAMSequenceRecord arg0) {
					return getRecords(context.getFileSystemContext().getSoftClipBamForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName()));
				}
			})));
		}
		public List<SAMRecord> getMate(final SAMEvidenceSource source, String chr) {
			return getRecords(context.getFileSystemContext().getMateBamForChr(source.getFileIntermediateDirectoryBasedOn(), chr));
		}
		public List<SAMRecord> getMate(final SAMEvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<SAMRecord>>() {
				@Override
				public Iterable<SAMRecord> apply(SAMSequenceRecord arg0) {
					return getRecords(context.getFileSystemContext().getMateBamForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName()));
				}
			})));
		}
		public List<SAMRecord> getRSC(final SAMEvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<SAMRecord>>() {
				@Override
				public Iterable<SAMRecord> apply(SAMSequenceRecord arg0) {
					return getRecords(context.getFileSystemContext().getSoftClipRemoteBamForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName()));
				}
			})));
		}
		public List<SAMRecord> getRRR(final SAMEvidenceSource source) {
			return Lists.newArrayList(Iterables.concat(Iterables.transform(context.getDictionary().getSequences(), new Function<SAMSequenceRecord, Iterable<SAMRecord>>() {
				@Override
				public Iterable<SAMRecord> apply(SAMSequenceRecord arg0) {
					return getRecords(context.getFileSystemContext().getRealignmentRemoteBamForChr(source.getFileIntermediateDirectoryBasedOn(), arg0.getSequenceName()));
				}
			})));
		}
	}
}
