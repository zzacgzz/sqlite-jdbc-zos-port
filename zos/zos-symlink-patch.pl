#!/usr/bin/perl
# z/OS port patch for the SQLite amalgamation (sqlite3.c).
#
# SQLite's unix VFS expands symlinks itself when building a full
# pathname (appendOnePathElement). z/OS system symlinks such as
#   /tmp -> $SYSNAME/tmp
# contain a symbol that only the kernel substitutes, so SQLite builds
# "/$SYSNAME/tmp/..." and the open fails with SQLITE_CANTOPEN.
#
# This leaves any symlink whose target starts with '$' unexpanded and
# lets the kernel resolve it. Only active when __MVS__ is defined.
#
# Usage: perl zos-symlink-patch.pl zos-build/sqlite3.c
use strict;
use warnings;

my $f = shift or die "usage: $0 path/to/sqlite3.c\n";
open my $in, '<', $f or die "$f: $!\n";
binmode $in;
my $src = do { local $/; <$in> };
close $in;

if ($src =~ /ZOS_SYMLINK_PATCH/) {
  print "already patched\n";
  exit 0;
}

my $n = ($src =~ s{(\n([ \t]*)zLnk\[got\] = 0;\n)}{$1$2/* ZOS_SYMLINK_PATCH: leave \$SYSNAME/\$VERSION links to the kernel */\n#ifdef __MVS__\n$2if( zLnk[0]=='\$' ) return;\n#endif\n}g);

die "expected exactly 1 match for 'zLnk[got] = 0;', found $n - not patched\n"
  unless $n == 1;

open my $out, '>', $f or die "$f: $!\n";
binmode $out;
print $out $src;
close $out;
print "patched OK\n";
