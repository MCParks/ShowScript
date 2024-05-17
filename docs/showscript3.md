## Introduction 
ShowScript 3 is a programming language that allows you to schedule commands and other actions to be executed at specified timecodes. Here's what it looks like:


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

## Basic Concepts

At its core, a ShowScript is a text file (ending in the `.groovy` extension) that defines what actions should happen and when. The file is broken up into blocks of "Timecodes" with one or more blocks of "Actions" inside of them to be executed at that timecode.

### Timecodes

A Timecode block may be specified multiple ways:

#### Ticks `(ticks)`
The fundamental time unit of the Minecraft server. 20 ticks is equal to 1 second.
```groovy
ticks(20) {
...
}
```

#### Seconds (`seconds`)
For you convenience, you can also use seconds as a unit for your timecode block:
```groovy
seconds(1) {
...
}
```
Fractional seconds are also supported:

```groovy
seconds(1.5) {
...
}
```

If you provide a fractional second that does not line up evenly on a tick, it will be rounded down to the nearest tick.

> Multiple actions in one tick will not always be run in the same order as they are written in the show file.
{.is-warning}

### Actions
Inside of your Timecode blocks, you can set any number of Actions to be executed at that timecode. ShowScript 3 supports a few different types of actions:

#### Command (`cmd`) 
Run a command in the console of your server. Any command from any plugin that can be run from console can be used! On MCParks, we primarily use commands from in-house custom plugins.
```groovy
cmd {
 "any command you can run in console"
}
```

#### Text (`text`)
Display a piece of `text` to all players within `range` blocks of `x`/`y`/`z` in world `world`
```groovy
text {
  world = "world"
  x = 0
  y = 0
  z = 0
  text = "your text here"
  range = 10
}
```

### Show (`show`)

Start another show (either ShowScript 2 or 3)!

```groovy
show {
  name = "showName"
  <argumentName> = <argValue> // arguments are only required if the show requires arguments. See the "Show arguments" section for more information
  // arguments are parsed as Strings right now; proper support for arbitrary scripts is coming soon
}

```

### Comments

It's important to write comments in your code so other people know what's going on! 

ShowScript 3 supports single line comments:
```groovy
// this is a comment
```

and multi-line comments:
```groovy
/* this
is
a 
comment
that spans
multiple
lines!
*/
```

### Show arguments

Sometimes, you may want to write a ShowScript that is reusable under different circumstances.

Say you want to write a show that sets the time of day using `/time set <timeOfDay>`, and you know you'll need one that runs `/time set day` and `/time set night`. You *could* write this as two separate shows:


```groovy
// setDay.groovy
ticks(0) {
  cmd {
    "time set day"
  }
}
```


```groovy
// setNight.groovy
ticks(0) {
  cmd {
    "time set night"
  }
}
```

But, ShowScript 3 lets you turn this show into a "function" that can be called with any input you want, like this:

```groovy
// setTime.groovy
show { timeOfDay ->
  ticks(0) {
    cmd {
      "time set ${timeOfDay}" 
    }
  }
}
```

You can then use the `--args` flag when starting your show to specify the argument to pass: `/show start setTime --args "day"`, for example (yes, you need to wrap the string `"day"` in quotes. If you're interested in why, check out the  . And since the `/time set <timeOfDay>` command can take *any* time of day, 


Your show can have more than one argument, too:
```groovy
// adder.groovy
show { num1, num2 ->
  ticks(0) {
    cmd {
      "broadcast ${num1 + num2}" 
    }
  }
}
```
You would start this show with `/show start adder --args 1, 2` if you want `/broadcast 3` to be executed. Multiple arguments are separated with commas (`,`). 
## Advanced Concepts

### Variables

Sometimes there will be a string, number, or some other piece of data that repeats _constantly_ in your shows (coordinates, an armorstand's name, preamble to an `imagemap animate` command, etc). It's bad style to repeat this code over and over again, so you can use variables to avoid repetition.

#### Variable definition and assignment

Variables can be defined using the `def` keyword followed by a variable name:
```groovy
def myCoolVariable

```

You can assign variables for later use:
```groovy
myCoolVariable = 5
```

You can also definite and assign a variable in one step, like this:
```groovy
def myCoolVariable = 5

def approximatelyPi = 3.1415

def isRyanCool = true
```

Variable definition and assignment can happen outside of a timecode block and can be used anywhere in your show (it will be initialized when `/show start` is initially called)
```groovy
def imagemapAnimate = "imagemap animate 163 55 703 https://mcparks.us/images/WDW/MK/Tomorrowland/Space/Preshow1"

ticks(0) {
  cmd {
    "${imagemapAnimate}/001.png"
  }
}

ticks(10) {
  cmd {
    "${imagemapAnimate}/002.png"
  }
}
```

Variable definition and assignment can happen in a timecode block, too, but the variable can only be used in that block (it will be initialized when the timecode block runs)
```groovy
ticks(0) {
  def location = "105 65 222"
  cmd {
    "asa animate ${location} ..."
  }
  
  cmd {
    "imagemap animate ${location} ..."
  }
}

ticks(10) {
  // this will produce an error
  cmd {
    "broadcast location is ${location}"
  }
}

```

You can also define a variable outside a timecode block, but change its value in different timecode blocks:

```groovy
def theNumber = 0
ticks(1) {
  theNumber = theNumber + 1
}

ticks(2) {
  theNumber = theNumber + 1
}

ticks(3) {
  cmd {
    "broadcast theNumber is ${theNumber}" // will broadcast "theNumber is 2"
  }
}

```




### Programming

A lot of the power of ShowScript 3 comes from the fact that it's powered by the Groovy programming language. All [semantics](https://groovy-lang.org/semantics.html) of the Groovy programming language, including `if` statements/conditionals, `for`/`while` loops, and more. 

> It's important that any code you run is PERFORMANT! If you think the code you're running might be resource intensive (looping over a lot of entities, running intense math every single tick, etc), ask in #technicians for guidance on how to accomplish your goal as performantly as possible!
{.is-warning}

Here are examples of this power:

```groovy
def imagemapAnimate = "imagemap animate 163 55 703 https://mcparks.us/images/WDW/MK/Tomorrowland/Space/Preshow1" // preamble for an imagemap animate command in the Space Mountain preshow monitor

for (int i=1; i<=42; i++) { // there are 42 frames in this video, named `001.png` to `042.png`
  def formattedNumber = String.format("%03d", i) // use Java's String formatting (which Groovy inherits) to format the number to 3 digits
  ticks(i*10) { // create a timecode block for every 10 ticks
    cmd {
      "${imagemapAnimate}/${formattedNumber}.png" // the commmand is the preamble we defined on line 1, followed by a '/', followed by the number of the frame, followed by .png
    }
  }
}
```



### Global Variables

ShowScript 3 has the ability to get and set variables that can be accessed across shows. Currently, these values will not persist across server restarts.

`setGlobalVariable(String name, Object value)`: 
`getGlobalVariable(String name)`:
`getGlobalVariable(String name, Object defaultValue)`:

```groovy
// testShow.groovy

ticks(0) {
  setGlobalVariable("x", 5)
}
```

```groovy
// test2.groovy
ticks(0) {
  def x = getGlobalVariable("x") // this will be 5
  def y = getGlobalVariable("y") // this will be `null`
  def yWithDefaultValue = getGlobalVariable("y", 10) // this will default to 10
}
```

### Sharing data between shows: `export` and `load`

// TODO: add

### Accessing server info

ShowScript 3 has some convenience methods for accessing information from the Minecraft server that you can use in programming to make certain decisions. 
> Be careful when accessing server information, especially when running methods on objects on `World`s, `Player`s, etc -- these methods have the power to change literally _anything_ on the server! 
{.is-warning}




#### `world(String worldName)` (returns: [World](https://helpch.at/docs/1.12.2/org/bukkit/World.html))

Example usage:

```groovy
ticks(0) {
  def time = world("world").getTime()
  if (time > 12000) {
  	cmd {
      "broadcast it is night time"
    }
  } else {
    cmd {
      "broadcast it is day time"
    }
  }
}
```

#### `player(String playerName)` (returns: [Player](https://helpch.at/docs/1.12.2/org/bukkit/entity/Player.html))

Example usage:
```groovy
ticks(0) {
  def ryanLocation = player("RyanHecht_").getLocation()
  
  cmd {
    "broadcast Ryan is at ${ryanLocation.getX()}, ${ryanLocation.getY()}, ${ryanLocation.getZ()}"
  }
}
```
#### `location(String worldName, double x, double y, double z)` (returns: [Location](https://helpch.at/docs/1.12.2/org/bukkit/Location.html))

#### `location(World world, double x, double y, double z)`
#### `location(double x, double y, double z)` (creates a location in the world called "world")

#### `onlinePlayers()` (returns List\<Player\>)
Get a list of all the players on the server

#### `runningShows()` (returns List\<ShowScheduler\>)
Get a list of all running shows

`ShowScheduler` has these methods:
```java
  public String getName();

  public int getSyntaxVersion();

  public Integer getShowTaskId();

  public void stopShow();

  public int getTimecode();
 ```

#### `isShowRunning(String showName)` returns `boolean`
Returns `true` if a show of that name is running, otherwise `false`

### Other convenience methods

#### `sin(double angle)` (returns double)
Get the mathematical sine of an angle

#### `cos(double angle)` (returns double)
Get the mathematical cosine of an angle

#### `tan(double angle)` (returns double)
Get the mathematical tangent of an angle
