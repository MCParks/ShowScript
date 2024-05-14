# ShowScript

ShowScript is a Spigot plugin that defines a programming language (v2 is backed by [YAML](https://yaml.org/) and v3 is a [Groovy DSL](http://docs.groovy-lang.org/docs/latest/html/documentation/core-domain-specific-languages.html)) that allows console commands and other actions to be scheduled to run at specified timecodes.
A file that defines a series of timecodes and actions is called a "Show."

## Example

### ShowScript 3

```groovy
ticks(0) {
  cmd {
    "broadcast Hello World!"
  }
}

seconds(2) {
  cmd {
    "broadcast This will run 2 seconds after the show starts"
  }
}
```


### ShowScript 2
```yaml
time0:
  - item: 'cmd'
    cmd: "broadcast Hello World!"
time40:
  - item: 'cmd'
    cmd: "broadcast This will run 2 seconds after the show starts"
```

## Command Usage

All of these commands require the `castmember` permission:

- `show start <showName>`: Starts a specific show. Replace `<showName>` with the name of the show you want to start. This command also accepts optional flags:
  - `--log`: Enables logging of actions. The sender of the command will receive chat messages whenever a Show Action executes.
  - `--async`: Runs the show asynchronously. Individual Show Actions will still run synchronously.
  - `--startAt <timecode>`: Specifies the timecode (in ticks) at which to start the show.
  - `--args`: Specifies additional arguments for the show, if the show requires them
  Usage: `show start <showName> [--log] [--async] [--startAt <timecode>] [--args <arguments>]`.

- `show stop <showName>`: Stops all instances of a specific show. Replace `<showName>` with the name of the show you want to stop. 

- `show toggledebug`: Toggles the debug mode. It doesn't require any arguments. 

- `show stopall`: Stops all currently running shows. 

- `show list`: Lists all currently running shows. 


## Licensing

ShowScript is licensed under the [GNU AFFERO GENERAL PUBLIC LICENSE](https://github.com/MCParks/ShowScript/blob/main/LICENSE). This means that you MUST make available the complete source code of any derived works under the same license. If you use a modified version of this plugin on a Minecraft server, you must make
the complete source code of the modified version available under the same license to all users of your server. We'd appreciate if you did so with a [pull request!](https://github.com/MCParks/ShowScript/pulls)
