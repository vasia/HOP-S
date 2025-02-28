The Hadoop Online (HOP) is a modified version of Hadoop
that supports pipelining between tasks and between jobs. For more
information on HOP, please see our website:

   http://code.google.com/p/hop/

To enable HOP-specific functionality (e.g. pipelining), use following
settings:

enable pipelining between mappers and reducers
[mapred.map.pipeline = (boolean) true / false]

determine the frequency of reducers output. Can be set between 1 and
100 % (values of 0.01 and 1 accordingly)
[mapred.snapshot.frequency = (float) 0.0 to 1.0]

enable input file shuffling for reduced bias 
[io.file.shuffle = (boolean) true / false]

set up the block level sampling rate (the number of files each sample
should consist of). Default value = 4. Maximum value = total number of 
input files. Minimum value = 1 (no sampling).
[io.split.maxsubsplit = (int) 1 to noOfInputFiles]

NOTICE:
To enable block level sampling jobs must be configured to use specific
input format: RandTextInputFormat.class It can be set as following:
[JobConf.setInputFormat(RandTextInputFormat.class)]
One of examples (topkwordcount) does have it set by default, so can
be executed without recompiling.
