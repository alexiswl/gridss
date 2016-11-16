package au.edu.wehi.idsv.pipeline;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

import au.edu.wehi.idsv.DirectedEvidence;
import au.edu.wehi.idsv.FileSystemContext;
import au.edu.wehi.idsv.IntermediateFileUtil;
import au.edu.wehi.idsv.ProcessStep;
import au.edu.wehi.idsv.ProcessingContext;
import au.edu.wehi.idsv.RealignedRemoteSoftClipEvidence;
import au.edu.wehi.idsv.RealignedSoftClipEvidence;
import au.edu.wehi.idsv.SAMEvidenceSource;
import au.edu.wehi.idsv.SpannedIndelEvidence;
import au.edu.wehi.idsv.sam.SAMFileUtil;
import au.edu.wehi.idsv.sam.SAMFileUtil.SortCallable;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.Log;

public class SortRealignedSoftClips extends DataTransformStep {
	private static final Log log = Log.getInstance(SortRealignedSoftClips.class);
	private final SAMEvidenceSource source;
	private final List<SAMFileWriter> scwriters = Lists.newArrayList();
	private final List<SAMFileWriter> realignmentWriters = Lists.newArrayList();
	public SortRealignedSoftClips(ProcessingContext processContext, SAMEvidenceSource source) {
		super(processContext);
		this.source = source;
	}
	public synchronized boolean isComplete() {
		List<File> targetFile = new ArrayList<File>();
		List<File> sourceFile = new ArrayList<File>();
		FileSystemContext fsc = processContext.getFileSystemContext();
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				sourceFile.add(null); targetFile.add(fsc.getSoftClipRemoteBamForChr(source.getSourceFile(), seq.getSequenceName()));
				sourceFile.add(null); targetFile.add(fsc.getRealignmentRemoteBamForChr(source.getSourceFile(), seq.getSequenceName()));
			}
		} else {
			sourceFile.add(null); targetFile.add(fsc.getSoftClipRemoteBam(source.getSourceFile()));
			sourceFile.add(null); targetFile.add(fsc.getRealignmentRemoteBam(source.getSourceFile()));
		}
		return IntermediateFileUtil.checkIntermediate(targetFile, sourceFile);
	}
	@Override
	public synchronized void process(EnumSet<ProcessStep> steps) {
		if (isComplete()) {
			log.debug("SortRealignedSoftClips: no work to do for ", source.getSourceFile());
		}
		if (!canProcess()) {
			String msg = String.format("Soft clip realignment for %s not completed. Unable to process", source.getSourceFile());
			log.error(msg);
			throw new IllegalStateException(msg);
			// return EnumSet.of(ProcessStep.SORT_REALIGNED_SOFT_CLIPS);
		}
		try {
			log.info("START: sorting mapped soft clips for ", source.getSourceFile());
			createUnsortedOutputWriters();
			writeUnsortedOutput();
			close();
			sort();
			deleteTemp();
			log.info("SUCCESS: sorting mapped soft clips for ", source.getSourceFile());
		} catch (Exception e) {
			String msg = String.format("Unable to sort mapped soft clips for %s", source.getSourceFile());
			log.error(e, msg);
			close();
			deleteTemp();
			deleteOutput();
			throw new RuntimeException(msg, e);
			// return EnumSet.of(ProcessStep.SORT_REALIGNED_SOFT_CLIPS);
		}
	}
	private void sort() throws IOException {
		FileSystemContext fsc = processContext.getFileSystemContext();
		List<SortCallable> actions = Lists.newArrayList();
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				actions.add(new SAMFileUtil.SortCallable(processContext.getFileSystemContext(),
						fsc.getSoftClipRemoteUnsortedBamForChr(source.getSourceFile(), seq.getSequenceName()),
						fsc.getSoftClipRemoteBamForChr(source.getSourceFile(), seq.getSequenceName()),
						new RealignedSoftClipEvidence.RealignmentCoordinateComparator(),
						header -> header));
				actions.add(new SAMFileUtil.SortCallable(processContext.getFileSystemContext(),
						fsc.getRealignmentRemoteUnsortedBamForChr(source.getSourceFile(), seq.getSequenceName()),
						fsc.getRealignmentRemoteBamForChr(source.getSourceFile(), seq.getSequenceName()),
						SortOrder.coordinate,
						header -> header));
			}
		} else {
			actions.add(new SAMFileUtil.SortCallable(processContext.getFileSystemContext(),
					fsc.getSoftClipRemoteUnsortedBam(source.getSourceFile()),
					fsc.getSoftClipRemoteBam(source.getSourceFile()),
					new RealignedSoftClipEvidence.RealignmentCoordinateComparator(),
					header -> header));
			actions.add(new SAMFileUtil.SortCallable(processContext.getFileSystemContext(),
					fsc.getRealignmentRemoteUnsortedBam(source.getSourceFile()),
					fsc.getRealignmentRemoteBam(source.getSourceFile()),
					SortOrder.coordinate,
					header -> header));
		}
		// PARALLEL opportunity - not great candidate due memory usage of sorting
		for (SortCallable c : actions) {
			c.call();
		}
	}
	@Override
	public synchronized void close() {
		for (SAMFileWriter w : scwriters) {
			w.close();
		}
		scwriters.clear();
		for (SAMFileWriter w : realignmentWriters) {
			w.close();
		}
		realignmentWriters.clear();
		super.close();
	}
	private void writeUnsortedOutput() {
		Iterator<RealignedSoftClipEvidence> it = Iterators.filter(source.iterator(false, true, false), RealignedSoftClipEvidence.class);
		while (it.hasNext()) {
			DirectedEvidence de = it.next();
			if (de instanceof RealignedRemoteSoftClipEvidence) continue;
			if (de instanceof SpannedIndelEvidence) continue;
			RealignedSoftClipEvidence evidence = (RealignedSoftClipEvidence)de;
			scwriters.get(evidence.getBreakendSummary().referenceIndex2 % scwriters.size()).addAlignment(evidence.getSAMRecord());
			realignmentWriters.get(evidence.getBreakendSummary().referenceIndex2 % realignmentWriters.size()).addAlignment(evidence.getRealignedSAMRecord());
		}
	}
	private void createUnsortedOutputWriters() {
		FileSystemContext fsc = processContext.getFileSystemContext();
		SAMFileHeader header = source.getHeader().clone();
		header.setSortOrder(SortOrder.unsorted);
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				scwriters.add(processContext.getSamFileWriterFactory(false).makeSAMOrBAMWriter(header, true, fsc.getSoftClipRemoteUnsortedBamForChr(source.getSourceFile(), seq.getSequenceName())));
				realignmentWriters.add(processContext.getSamFileWriterFactory(false).makeSAMOrBAMWriter(header, true, fsc.getRealignmentRemoteUnsortedBamForChr(source.getSourceFile(), seq.getSequenceName())));
			}
		} else {
			scwriters.add(processContext.getSamFileWriterFactory(false).makeSAMOrBAMWriter(header, true, fsc.getSoftClipRemoteUnsortedBam(source.getSourceFile())));
			realignmentWriters.add(processContext.getSamFileWriterFactory(false).makeSAMOrBAMWriter(header, true, fsc.getRealignmentRemoteUnsortedBam(source.getSourceFile())));
		}
	}
	@Override
	protected Log getLog() {
		return log;
	}
	@Override
	public List<File> getInputs() {
		return ImmutableList.of();
	}
	@Override
	public boolean canProcess() {
		return source.isComplete(ProcessStep.REALIGN_SOFT_CLIPS);
	}
	@Override
	public List<File> getOutput() {
		List<File> outputs = Lists.newArrayList();
		FileSystemContext fsc = processContext.getFileSystemContext();
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				outputs.add(fsc.getSoftClipRemoteBamForChr(source.getSourceFile(), seq.getSequenceName()));
				outputs.add(fsc.getRealignmentRemoteBamForChr(source.getSourceFile(), seq.getSequenceName()));
			}
		} else {
			outputs.add(fsc.getSoftClipRemoteBam(source.getSourceFile()));
			outputs.add(fsc.getRealignmentRemoteBam(source.getSourceFile()));
		}
		return outputs;
	}
	@Override
	public List<File> getTemporary() {
		List<File> files = Lists.newArrayList();
		FileSystemContext fsc = processContext.getFileSystemContext();
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				files.add(fsc.getSoftClipRemoteUnsortedBamForChr(source.getSourceFile(), seq.getSequenceName()));
				files.add(fsc.getRealignmentRemoteUnsortedBamForChr(source.getSourceFile(), seq.getSequenceName()));
			}
		} else {
			files.add(fsc.getSoftClipRemoteUnsortedBam(source.getSourceFile()));
			files.add(fsc.getRealignmentRemoteUnsortedBam(source.getSourceFile()));
		}
		return files;
	}
}
