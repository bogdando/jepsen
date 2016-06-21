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

## How-to run tests from the Fuel master as a Jepsen control node

Install a [Fuel](https://wiki.openstack.org/wiki/Fuel) environment (dev/QA!)
with at least a few OpenStack controllers. Here we assume controllers with
FQDNs `node-{1,2,3}.test.domain.local`. Also hardcoded in the `nodes` list.

Allow docker traffic masquerading:
```
puppet apply -e "firewall { '004 forward_docker_net':
    chain      => 'POSTROUTING',
    table      => 'nat',
    proto      => 'all',
    source     => '172.17.0.0/16',
    outiface  => 'e+',
    jump       => 'MASQUERADE',
  }"
puppet apply -e "firewall { '050 forward docker_net':
    chain    => 'FORWARD',
    proto    => 'all',
    source   => '172.17.0.0/16',
    iniface  => 'docker+',
    action   => 'accept',
  }"
```

Get the repo with helper scripts:
```
yum install git
git clone https://github.com/bogdando/pacemaker-cluster-ocf-vagrant
cd pacemaker-cluster-ocf-vagrant
```

Get the Jepsen fork and prepare nodes for Jepsen tests.
NOTE: it allows password based root login for the env nodes!
```
./vagrant_script/conf_jepsen.sh fuel
cat /var/lib/cobbler/cobbler_hosts >> /etc/hosts
for i in $(cat /var/lib/cobbler/cobbler_hosts | awk '{print $2}'); do
  ssh-keyscan -t rsa $i >> /root/.ssh/known_hosts
  ssh $i "sed -i '/PasswordAuthentication/d' /etc/ssh/sshd_config"
  ssh $i service ssh restart
done
```

Run tests as:
```
PURGE=true ./vagrant_script/lein_test.sh noop ssh-test
```
Note, use the PURGE for the very first run. It shall produce a custom
Jepsen jar build and relaunch the jepsen with lein container. You can skip this
step for later runs as well. Also see the usage examples above, but prefixed
with the `docker exec -it jepsen bash -c`, f.e.:
```
docker exec -it jepsen bash -c "TESTPROC=rsyslogd FDURATION=10 FWAIT=1
FTIME=20 lein test :only jepsen.noop-test/factors-freeze-test"
docker exec -it jepsen lein test :only jepsen.noop-test/factors-netpart-test
```

To watch how it affects the fuel nodes, you can run `crm_mon` on controllers,
or something like:
```
watch "crm_node -p; crm resource show clone_p_mysqld; crm resource show
master_p_rabbitmq-server"
```

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
