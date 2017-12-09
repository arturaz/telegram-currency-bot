## What is this?

It's a simple currency watcher bot, which alerts you when it is best 
time to exchange your currency.

## How do I run it?

You'll need a telegram bot api key and currency layer api key (or implement
your own rates provider).

When you have those, the simplest way is to:

```
$ sbt run
``` 

More likely you want to run it on a server. I run it on an Ubuntu server:

```
$ sbt debian:packageBin

Move the package into the server, then install it on the server:
# dpkg -i telegram-currency-watcher-x.y.z.deb

It runs as a daemon now (/etc/init.d/telegram-currency-watcher)
Logs are in /var/log/telegram-currency-watcher/
State file is in /var/run/telegram-currency-watcher/
```

## Why is it so hacky?

Because it has been quickly thrown together. If you want to extend it
you are welcome to clean it up and submit a merge request. Thanks!