package edu.unc.bioinf.ubu.assembly;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import net.sf.samtools.SAMRecord;

/**
 * Assembles long contigs from a (relatively small) SAM file.
 * 
 * @author Lisle Mose (lmose at unc dot edu)
 */
public class Assembler {
		
	private int kmerSize;
	private int minEdgeFrequency;
	private int minNodeFrequncy;

	private int minContigLength;
	private double minContigRatio;
	
	private double minEdgeRatio;
	
	private int minUniqueReads = 1;
	
	private boolean shouldTruncateOutputOnRepeat = true;
	
	private int maxPotentialContigs = 500;
	
	private int minMergeSize = 25;
	
	private Map<Sequence, Node> nodes = new HashMap<Sequence, Node>();
	
	private Set<Node> rootNodes = new HashSet<Node>();
	
	private List<Contig> contigs = new ArrayList<Contig>();
	
	private BufferedWriter writer;
	
	private int potentialContigCount = 0;
	
	private long regionLength;
	
	private boolean hasRepeat = false;
	
	int outputCount = 0;
	
	private boolean isEmpty = true;
	
	//TODO: Do not keep contigs in memory.
	public boolean assembleContigs(String inputSam, String output, String prefix) throws FileNotFoundException, IOException, InterruptedException {
        SAMFileReader reader = new SAMFileReader(new File(inputSam));
        reader.setValidationStringency(ValidationStringency.SILENT);
		
		long regionStart = Long.MAX_VALUE;
		long regionEnd   = -1;
		
		int numRecs = 0;
		
		int count = 0;
		
		int ambiguousCount = 0;
		
		for (SAMRecord read : reader) {
			
			//TODO: Disallow anything other than ATCG?
			boolean hasAmbiguousBases = read.getReadString().contains("N");
			Integer numBestHits = (Integer) read.getIntegerAttribute("X0");
			boolean hasAmbiguousInitialAlignment = numBestHits != null && numBestHits > 1;
			
			if (!hasAmbiguousBases && !hasAmbiguousInitialAlignment) {
				addToGraph(read);
				numRecs++;
				
				if (read.getAlignmentStart() < regionStart) {
					regionStart = read.getAlignmentStart();
				}
				
				if (read.getAlignmentEnd() > regionEnd) {
					regionEnd = read.getAlignmentEnd();
				}
			} else {
				ambiguousCount += 1;
			}
			
			count +=1;
			if ((count % 10000) == 0) {
				System.out.println(prefix + " - Assembler processed: " + count + " reads.");
				
//				if (count == 100000) {
//					try {
//						Thread.sleep(10000000);
//					} catch (InterruptedException e) {
//						e.printStackTrace();
//					}
//				}
			}
		}
		
		System.out.println("Assembler processed: " + count + " reads, skipping: " + 
				ambiguousCount + " ambiguous reads.");
		
		regionLength = regionEnd - regionStart;
		
		System.out.println("Num records: " + numRecs + ", Num nodes: " + nodes.size());
		System.out.println("Region length: " + regionLength);
				
//		printEdgeCounts();
		
//		filterLowFrequencyEdges();
		filterLowFrequencyNodes();
		
		identifyRootNodes();
		
		boolean shouldTruncateOutput = false;
		
		writer = new BufferedWriter(new FileWriter(output, false));
		
		try {
			buildContigs(prefix);
	//		mergeContigs();
//			outputContigs(prefix);
		} catch (DepthExceededException e) {
			System.out.println("DEPTH_EXCEEDED for : " + inputSam);
			contigs.clear();
			shouldTruncateOutput = true;
		} catch (TooManyPotentialContigsException e) {
			System.out.println("TOO_MANY_CONTIGS for : " + inputSam);
			contigs.clear();
			shouldTruncateOutput = true;
		} finally {
			writer.close();
			reader.close();
		}
		
		if (hasRepeat && shouldTruncateOutputOnRepeat) {
			System.out.println("REPEATING_NODE for : " + inputSam);
			shouldTruncateOutput = true;
		}
		
		if (shouldTruncateOutput) {
			// truncate the contig file
			truncateFile(output);
		}
		
		return !isEmpty;
	}
	
	private void truncateFile(String file) throws InterruptedException, IOException {
		FileUtil.truncateFile(file);
	}
	
	public void setKmerSize(int kmerSize) {
		this.kmerSize = kmerSize;
	}
	
	public void setTruncateOutputOnRepeat(boolean truncateOutputOnRepeat) {
		this.shouldTruncateOutputOnRepeat = truncateOutputOnRepeat;
	}
	
	public void setMinContigLength(int minContigLength) {
		this.minContigLength = minContigLength;
	}

	public void setMinEdgeFrequency(int minEdgeFrequency) {
		this.minEdgeFrequency = minEdgeFrequency;
	}

	public void setMinNodeFrequncy(int minNodeFrequncy) {
		this.minNodeFrequncy = minNodeFrequncy;
	}
	
	public void setMinEdgeRatio(double minEdgeRatio) {
		this.minEdgeRatio = minEdgeRatio;
	}
	
	public void setMaxPotentialContigs(int maxContigs) {
		this.maxPotentialContigs = maxContigs;
	}
	
	public void setMinContigRatio(double minContigRatio) {
		this.minContigRatio = minContigRatio;
	}
	
	public void setMinUniqueReads(int minUniqueReads) {
		this.minUniqueReads = minUniqueReads;
	}

	private void filterLowFrequencyNodes() {
		List<Node> nodesToFilter = new ArrayList<Node>();
		
		for (Node node : nodes.values()) {
			if (node.getCount() < minNodeFrequncy) {
				nodesToFilter.add(node);
			} else if (!node.hasMultipleUniqueReads()) {
				nodesToFilter.add(node);
			}
		}
		
//		List<Edge> edgesToFilter = new ArrayList<Edge>();
		
		for (Node node : nodesToFilter) {
//			edgesToFilter.addAll(node.getToEdges());
//			edgesToFilter.addAll(node.getFromEdges());
			node.remove();
		}
		
//		for (Edge edge : edgesToFilter) {
//			edge.remove();
//		}
		
		for (Node node : nodesToFilter) {
			nodes.remove(node.getSequence());
		}
	}
	
	/*
	private void filterLowFrequencyEdges() {
		
		Set<Edge> edgesToFilter = new HashSet<Edge>();
		
		for (Node node : nodes.values()) {
			for (Edge edge : node.getToEdges()) {
				if (edge.getCount() < minEdgeFrequency) {
					edgesToFilter.add(edge);
				}
			}
			
			edgesToFilter.addAll(node.getInfrequentEdges(minEdgeRatio));
		}
		
		for (Edge edge : edgesToFilter) {
			edge.remove();
		}
	}
	*/
	
	private void outputContigs(String prefix) throws IOException {
		
//		System.out.println("Writing " + contigs.size() + " contigs.");
		
		for (Contig contig : contigs) {
			outputContig(contig, prefix);
		}
		
		contigs.clear();
	}
	
	private void outputContig(Contig contig, String prefix) throws IOException {
		contig.setDescriptor(prefix + "_" + outputCount++ + "_" + contig.getDescriptor());
		writer.append(">" + contig.getDescriptor() + "\n");
		writer.append(contig.getSequence());
		writer.append("\n");
		
		isEmpty = false;
	}
	
	private void identifyRootNodes() {
		for (Node node : nodes.values()) {
			if (node.isRootNode()) {
				rootNodes.add(node);
			}
		}
	}
	
	private void buildContigs(String prefix) throws IOException {
		System.out.println("Num root nodes: " + rootNodes.size());
		
		potentialContigCount = rootNodes.size();
		
		for (Node node : rootNodes) {
			//StringBuffer contig = new StringBuffer();
			Contig contig = new Contig();
			Set<Node> visitedNodes = new HashSet<Node>();
			Counts counts = new Counts();
			buildContig(node, visitedNodes, contig, counts);
			
			//TODO: Check for repeat and discard contigs if encountered
			outputContigs(prefix);
		}
		
		System.out.println("Potential contig count: " + potentialContigCount);
		System.out.println("Wrote: " + outputCount + " contigs.");
	}
	
	private void processContigTerminus(Node node, Counts counts, Contig contig) {
		
		if (!counts.isTerminatedAtRepeat()) {
			// We've reached the terminus, append the remainder of the node.
			contig.append(node, node.getSequence().getSequenceAsString());
		} else {
			hasRepeat = true;
		}
		
		if (contig.getSequence().length() >= minContigLength) {
			
			// Check contig length against region length if mcr is specified and we have a valid region length
			if ( (minContigRatio <= 0) || (regionLength <= 0) ||
				 (((double) contig.getSequence().length() / (double) regionLength) >= minContigRatio) ) {
				
				contig.setDescriptor(counts.toString());
				
				if (counts.isTerminatedAtRepeat()) {
					contig.setDescriptor(contig.getDescriptor() + "_repeatNode:" + node.getSequence().getSequenceAsString());
				}
				
				contigs.add(contig);
			}
		}
	}
	
	private void buildContig(Node node, Set<Node> visitedNodes, Contig contig, Counts counts) {
		buildContig(node, visitedNodes, contig, counts, 0);
	}
	
	private void buildContig(Node node, Set<Node> visitedNodes, Contig contig, Counts counts, int depth) {
		
		if (depth > 10000) {
			throw new DepthExceededException(depth);
		}
		
		if (potentialContigCount > maxPotentialContigs) {
			throw new TooManyPotentialContigsException();
		}
		
		depth += 1;
		
		if (visitedNodes.contains(node)) {
			counts.setTerminatedAtRepeat(true);
			processContigTerminus(node, counts, contig);
		} else {
			visitedNodes.add(node);
			
//			Collection<Edge> edges = node.getToEdges();
			Collection<Node> toNodes = node.getToNodes();
			
			if (toNodes.isEmpty()) {
				processContigTerminus(node, counts, contig);

			} else {
				// Append current character
				contig.append(node, Character.toString(node.getSequence().getFirstCharacter()));
				
				potentialContigCount += toNodes.size() - 1;
				
				// Create a new contig branch for each edge
				for (Node toNode : toNodes) {
//					counts.incrementEdgeCounts(edge.getCount());
					Contig contigBranch = new Contig(contig);
					Set<Node> visitedNodesBranch = new HashSet<Node>(visitedNodes);
					buildContig(toNode, visitedNodesBranch, contigBranch, (Counts) counts.clone(), depth);
				}
			}			
		}
	} 
	
	// Merge contigs that overlap with < kmerSize bases
	// This addresses "smallish" gaps in the graph
	private void mergeContigs() {
		
		if (minMergeSize > kmerSize) {
			List<Contig> updatedContigs = new ArrayList<Contig>(contigs);
			
			int mergedCount = 0;
			
			for (Contig contig1 : contigs) {
				boolean isMerged = false;
				
				for (Contig contig2 : updatedContigs) {
					if (contig1 != contig2) {
						int overlapIdx = getOverlapIndex(contig1.getSequence(), contig2.getSequence());
						
						if (overlapIdx > -1) {
							contig2.prependSequence(contig1.getDescriptor(), contig1.getSequence());
							isMerged = true;
						}
					}
				}
				
				if (isMerged) {
					updatedContigs.remove(contig1);
					mergedCount += 1;
				}
			}
			
			this.contigs = updatedContigs;
			
			System.out.println("Merged: " + mergedCount + " overlapping contigs.");
		}
	}
	
	private int getOverlapIndex(String s1, String s2) {
		int strLenDiff = s2.length() - s1.length();
		
		// Default start to 0 or the length of s2 from the end of s1
		int start = strLenDiff > 0 ? strLenDiff : 0;
		
		// If minMergeSize from end of s1 is beyond start, update start
		start = Math.max(start, s1.length()-minMergeSize);
		
		for (int i=start; i<s1.length(); i++) {
			if (s2.startsWith(s1.substring(i))) {
				return i;
			}
		}
		
		return -1;
	}
	
	/*
	private void printEdgeCounts() {
		long[] edgeCounts = new long[nodes.size()];
		List<Integer> edgeSizes = new ArrayList<Integer>();
		
		int idx = 0;
		for (Node node : nodes.values()) {
			edgeCounts[idx++] = node.getToEdges().size();
			
			for (Edge edge : node.getToEdges()) {
				edgeSizes.add(edge.getCount());
			}
		}
		
		Arrays.sort(edgeCounts);
		System.out.println("Median edge count: " + edgeCounts[edgeCounts.length/2]);
		System.out.println("Max edge count: " + edgeCounts[edgeCounts.length-1]);
		System.out.println("Min edge count: " + edgeCounts[0]);
		Integer[] sizes = edgeSizes.toArray(new Integer[edgeSizes.size()]);
		Arrays.sort(sizes);
		System.out.println("Median edge size: " + sizes[sizes.length/2]);
		System.out.println("Max edge size: " + sizes[sizes.length-1]);
		System.out.println("Min edge size: " + sizes[0]);
	}
	*/
	
	private void addToGraph(SAMRecord read) {
		addToGraph(read.getReadString());		
	}
	
	private void addToGraph(String sequence) {
		Node prev = null;
		
		for (int i=0; i<=sequence.length()-kmerSize; i++) {
			String kmer = sequence.substring(i, i+kmerSize);
			Sequence kmerSequence = new Sequence(kmer);
			Node node = nodes.get(kmerSequence);
			if (node == null) {
				node = new Node(kmerSequence);
				nodes.put(kmerSequence, node);
			} else {
				node.incrementCount();
			}
			
			node.addReadSequence(sequence);
			
			if (prev != null) {
				prev.addToNode(node);
			}
			
			prev = node;
		}
	}
	
	public static void main(String[] args) throws Exception {
		long s = System.currentTimeMillis();
		
		Assembler ayc = new Assembler();
		//22
//		ayc.setKmerSize(33);
//		ayc.setMinEdgeFrequency(3);
//		ayc.setMinNodeFrequncy(3);
//		ayc.setMinContigLength(100);
//		ayc.setMinEdgeRatio(.015);
//		ayc.setMaxPotentialContigsPerRegion(1500);
//		ayc.setMinContigToRegionRatio(.75);
		
		ayc.setKmerSize(33);
		ayc.setMinEdgeFrequency(3);
		ayc.setMinNodeFrequncy(3);
		ayc.setMinContigLength(100);
		ayc.setMinEdgeRatio(.015);
		ayc.setMaxPotentialContigs(100000);
		ayc.setMinContigRatio(.2);
		
//		ayc.assembleContigs("/home/lisle/ayc/sim/sim1/chr21/chr21_37236845_37237045.bam", "/home/lisle/ayc/sim/sim1/chr21/1.fasta", "foo");
		
//		ayc.assembleContigs("/home/lmose/dev/ayc/sim/sim261/assem/small.bam", "/home/lmose/dev/ayc/sim/sim261/assem/small.fasta", "foo");
//		ayc.assembleContigs("/home/lmose/dev/ayc/sim/sim261/assem/chr1_6161649_6165689.bam", "/home/lmose/dev/ayc/sim/sim261/assem/chr1_6161649_6165689.fasta", "foo");
		
//		ayc.assembleContigs("/home/lmose/dev/ayc/sim/38/assem/3.bam", "/home/lmose/dev/ayc/sim/38/assem/reads.fasta", "foo"); 
		
		ayc.assembleContigs("/home/lmose/dev/ayc/sim/38/bwasw.bam", "/home/lmose/dev/ayc/sim/38/bwasw_new.fasta", "foo");
		
//		ayc.assemble("/home/lisle/ayc/case0/normal_7576572_7577692.fastq", "/home/lisle/ayc/case0/normal_33_05.fasta");
//		ayc.assemble("/home/lisle/ayc/case0/tumor_7576572_7577692.fastq", "/home/lisle/ayc/case0/tumor_33_05.fasta");

		
		//ayc.assemble("/home/lisle/ayc/tp53.fastq", "/home/lisle/ayc/tp53.fasta");
//		ayc.assemble("/home/lisle/ayc/run2/normal_7576572_7577692.fastq", "/home/lisle/ayc/run4/normal_33_05.fasta");
//		ayc.assemble("/home/lisle/ayc/run2/tumor_7576572_7577692.fastq", "/home/lisle/ayc/run4/tumor_33_05.fasta");
//		ayc.assemble("/home/lisle/ayc/case1/normal.fastq", "/home/lisle/ayc/case1/normal_33_05.fasta");
//		ayc.assemble("/home/lisle/ayc/case1/tumor.fastq", "/home/lisle/ayc/case1/tumor_33_05.fasta");
		
//		ayc.assemble("/home/lisle/ayc/case2/normal.fastq", "/home/lisle/ayc/case2/deeper/rcnormal_19_02.fasta");
//		ayc.assemble("/home/lisle/ayc/case2/tumor.fastq", "/home/lisle/ayc/case2/deeper/rctumor_19_02.fasta");
		
//		ayc.assemble("/home/lisle/ayc/case2/normal.fastq", "/home/lisle/ayc/case2/deeper/normal_77_02.fasta");
//		ayc.assemble("/home/lisle/ayc/case2/tumor.fastq", "/home/lisle/ayc/case2/deeper/tumor_77_02.fasta");
		
//		ayc.assemble("/home/lisle/ayc/case2/normal.fastq", "/home/lisle/ayc/case2/deeper/normal_77_02B.fasta");
//		ayc.assemble("/home/lisle/ayc/case2/tumor.fastq", "/home/lisle/ayc/case2/deeper/tumor_77_02B.fasta");

//		ayc.assemble("/home/lisle/ayc/case1/round2/tumor.bam", "/home/lisle/ayc/case1/round2/re_tumor");
		
//		ayc.assemble("/home/lisle/ayc/case2/round2/case2_tumor.bam", "/home/lisle/ayc/case2/round2/ra_tumor");
		
//		ayc.assemble("/home/lisle/ayc/case0/round2/case0_tumor.bam", "/home/lisle/ayc/case0/round2/ra_tumor");
		
//		ayc.assemble("/home/lisle/ayc/case2/realigned/ra_normal.fastq", "/home/lisle/ayc/case2/realigned/normal_33_02.fasta");
//		ayc.assemble("/home/lisle/ayc/case2/realigned/ra_tumor.fastq", "/home/lisle/ayc/case2/realigned/tumor_33_02.fasta");


		
		long e = System.currentTimeMillis();
		
		System.out.println("Elapsed secs: " + (e-s)/1000);
	}
	
	static class DepthExceededException extends RuntimeException {

		private int depth;
		
		public DepthExceededException(int depth) {
			this.depth = depth;
		}
		
		public int getDepth() {
			return depth;
		}
	}
	
	static class TooManyPotentialContigsException extends RuntimeException {
		
	}
}
