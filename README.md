
# ShowScript

ShowScript is a Spigot plugin that defines a programming language (v2 is backed by [YAML](https://yaml.org/) and v3 is a [Groovy DSL](http://docs.groovy-lang.org/docs/latest/html/documentation/core-domain-specific-languages.html)) that allows console commands and other actions to be scheduled to run at specified timecodes.
A file that defines a series of timecodes and actions is called a "Show."

The technicians on [MCParks](https://mcparks.us) use ShowScript to make our [fireworks shows](https://youtu.be/stlB3RJ-9bk?si=91eUGVjCAUdphm9u&t=36), [theatre attractions](https://youtu.be/h2XtPJ_GFyc?si=oiAxssTy4VmCnzSj&t=38), and to schedule effects to run on our [rides](https://youtu.be/GTIFRJBdtRo?si=bk25eUzyQfBPmJYT&t=402). 

Join the ShowScript Discord server for help & support: https://discord.gg/yNX8QdKNMA

**Currently, ShowScript has only been tested to work on Spigot/Paper 1.12.2. PR's welcome to extend support to other versions!**

## Example

A file that defines a series of timecodes and actions is called a "Show." Your shows live in `plugins/ShowScript/Shows`. You may nest your shows in directories; it's considered good practice to do this to organize different projects.
You can write your shows in Groovy (ShowScript 3), or YAML (ShowScript 2). The YAML syntax of ShowScript 2 might be easier for those unfamiliar with programming to understand, but ShowScript 3 unlocks more capabilities and advanced usage.

### ShowScript 3 (file ending in `.groovy`)

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
  cmd {
    "broadcast you can run as many commands as you want in a timecode"
  }
}

def blueFairyPrefix = "&1[&9Blue Fairy&1] &9"

ticks(967) {
  text {
    world = "world"
    x = -247
    y = 53
    z = 759
    range = 300
    text = "${blueFairyPrefix}When stars are born,"
  }
}

ticks(1010) {
  text {
    world = "world"
    x = -247
    y = 53
    z = 759
    range = 300
    text = "${blueFairyPrefix}they possess a gift or two."
  }
}

ticks(1076) {
  text {
    world = "world"
    x = -247
    y = 53
    z = 759
    range = 300
    text = "${blueFairyPrefix}One of them is this:"
  }
}

ticks(1110) {
  text {
    world = "world"
    x = -247
    y = 53
    z = 759
    range = 300
    text = "${blueFairyPrefix}they have the power to make a wish come true."
  }
}

ticks(1163) {
  cmd {
    "summon fireworks_rocket -221 70 689 {Motion:[-1.0,10.0,0.0],FireworksItem:{id:fireworks,Count:1,tag:{Fireworks:{Explosions:[{Type:4,Trail:1,Colors:[16776688],FadeColors:[16775387]}]}}}}"
  }
}
```


### ShowScript 2 (file ending in `.yml`)
```yaml
time0:
  - item: 'cmd'
    cmd: "broadcast Hello World!"
time40:
  - item: 'cmd'
    cmd: "broadcast This will run 2 seconds after the show starts"
  - item: 'cmd'
    cmd: "broadcast you can run as many commands as you want in a timecode"

macros:
  blueFairyPrefix: "&1[&9Blue Fairy&1] &9"

time967:
  - item: 'text'
    text: '^blueFairyPrefix^When stars are born,'
    x: -247.0
    y: 53.0
    z: 759.0
    range: 300.0
    world: world
time1010:
  - item: 'text'
    text: '^blueFairyPrefix^they possess a gift or two.'
    x: -247.0
    y: 53.0
    z: 759.0
    range: 300.0
    world: world
time1076:
  - item: 'text'
    text: '^blueFairyPrefix^One of them is this,'
    x: -247.0
    y: 53.0
    z: 759.0
    range: 300.0
    world: world
time1110:
  - item: 'text'
    text: '^blueFairyPrefix^they have the power to make a wish come true.'
    x: -247.0
    y: 53.0
    z: 759.0
    range: 300.0
    world: world
time1163:
  - item: 'cmd'
    cmd: summon fireworks_rocket -221 70 689 {Motion:[-1.0,10.0,0.0],FireworksItem:{id:fireworks,Count:1,tag:{Fireworks:{Explosions:[{Type:4,Trail:1,Colors:[16776688],FadeColors:[16775387]}]}}}}
```

## Command Usage

All of these commands require the `castmember` permission:

- `show start <showName>`: Starts a specific show. Replace `<showName>` with the name of the show you want to start (without the file extension). This command also accepts optional flags:
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
