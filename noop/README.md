# jepsen.noop

A Clojure library designed to run Nemesis only, in different modes,
on given nodes. There are no models, clients, checkers, but a pure
destruction.

## Usage

Casts SIGSTOP/SIGCONT on the mysqld processes of the n3 node. Starts (sends
SIGSTOP) after a 1 sec delay, stops (resumes with SIGCONT) 10 sec later, then
repeats, unless total of 20 seconds elapsed:
```
TESTPROC=mysqld TESTNODE=n3 FDURATION=10 FWAIT=1 FTIME=20 lein test :only \
jepsen.noop-test/factors-freeze-test
```
Casts SIGKILL on the beam processes of a randomly picked node with the
rest of params taking defaults:
```
TESTPROC=beam lein test :only jepsen.noop-test/factors-crashstop-test
```
Splits a given set of nodes into isolated random halves (network partitions):
```
lein test :only jepsen.noop-test/factors-netpart-test
```

TODO: add mode modes, create filters for subsets of nodes by misc criteria

## How-to run tests against custom node names/root password

By default, Jepsen hardcodes node names as n1, n2, ..n5 with root/root creds.
Define custom `nodes-root-pass` and `nodes` list in the `noop_test.clj` file.

TODO: read from a config file as well

To issue a basic SSH check, use
```
lein test :only jepsen.noop-test/ssh-test
```

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
