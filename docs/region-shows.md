# Region Shows

Region Shows allow you to have a show loop ambiently as long as players are inside a WorldGuard region. This is perfect for ambient effects. You must have WorldGuard installed on your server for this functionality to work.

## Region Show Schemas

Region Shows are controlled by YAML files called "Region Show Schemas." A Region Show Schema file may be placed anywhere in the show filesystem. Its file name must end with `_regionshowschema.yml` in order for the system to identify it as a Region Show Schema.

For the rest of this article, we will consider the example of a Technician working on an attraction called `myProject`. They want to have an ambient effect in the queue of the attraction of an animatronic, called "wavyboi," to wave to guests who pass him by in the queue.

The Technician decides makes a folder, `myProject/queue/waving_animatronic`, to house all the show files for the wavyboi effect.


### Region Show Schema Example
File: `myProject/queue/waving_animatronic/wavyboi_regionshowschema.yml`
```yaml
region: "myRegion"
setup: 'myProject/queue/waving_animatronic/setup'
loop:
  name: 'myProject/queue/waving_animatronic/loop'
  delay: 20
cleanup: 'myProject/queue/waving_animatronic/cleanup'
```

Region Show Schema files must end in `_regionshowschema.yml`. 
- `region` refers to the region that the region for which the show listener should be keeping track of player entrances/exits.
- `setup` refers to the show that should be run when the first player enters `region` (i.e. when the number of players in `region` goes from 0 to 1). See [`setup` Show Example](#setup-show-example) for more information.
- `loop.name` refers to the show that should continue playing on loop as long as there are players `region`. See [`loop` Show Example](#loop-show-example) for more information.
		- `loop.delay` exists because some commands take time to fully execute, and you don't want to have your `loop` show start over while a long-running command is still executing. We cover this more in [`loop` Show Example](#loop-show-example). The `loop.delay` field is optional and defaults to `0`. If you don't a delay between instances of your loop show, your schema can look like:
```yaml
region: "myRegion"
setup: 'myProject/queue/waving_animatronic/setup'
loop: 'path/to/loopShow/that/doesnt/need/a/delay'
cleanup: 'myProject/queue/waving_animatronic/cleanup'
```
- `shows.cleanup` refers to the show that should be run when the last player leaves `region` (i.e. when the number of players in `region` goes from 1 to 0). See [`cleanup` Show Example](#cleanup-show-example) for more information.


### `setup` Show Example
File: `myProject/queue/waving_animatronic/setup.yml`
```yaml
time0:
  - item: 'cmd'
    cmd: assave recall myProject/wavyboi
```

In this setup show, an [`assave`](/Technicians/ArmorStandAnimations#saving-armor-stands) is run to spawn in our `wavyboi` animatronic. The setup show is the place to put any commands you want to be run prior to the core loop -- armor stand spawns, laser/spotlight "create-angle" commands, traincarts spawns, etc.

### `loop` Show Example
File: `myProject/queue/waving_animatronic/loop.yml`
```yaml
time0:
  - item: 'cmd'
    cmd: 'asa animatecycle wavyboi -166 53 1103 10 larm 0 0 20 10'
```

In this loop show, we find an [asa animate](/Technicians/ArmorStandAnimations#animating-an-armor-stand) command to make our `wavyboi` wave. The loop show is the place to put all the actual "effect" commands that should be running on loop. 

Depending on the contents of your `loop` show, you might need to utilize the `loop.delay` field in the Region Show Schema file. This congfigurable `delay` (which is by default 0 ticks if you don't specify one) is the number of ticks the server should wait from the end of the `loop` show to when it should start another `loop` show. That is, after the final command of the `loop` show runs, should the server wait "extra" time before starting another copy of it?

You'll notice that in our example, the `delay` was set to `20`. This is because on the last tick of the loop show, we run an `asa animatecycle` command whose animation takes `10` ticks. Since it's an `animatecycle` (see [Animating an Armor Stand](/Technicians/ArmorStandAnimations#animating-an-armor-stand)), it runs the animation in reverse as well, meaning that the entire command takes 20 ticks to run. We want to be sure that there are not multiple copies of the animation command running on top of each other, so we set `loop.delay` to 20 ticks to ensure that the server waits an adequate amount of time before running the loop show again.  

The `loop.delay` field is OPTIONAL, and will default to `0` ticks if not specified. This means that as soon as an instance of the `loop` show has completed, it will start another instance of itself on the next tick.

### `cleanup` Show Example
File: `myProject/queue/waving_animatronic/cleanup.yml`
```yaml
time0:
  - item: 'cmd'
    cmd: 'askill wavyboi -166 53 1102 10'
```

In this cleanup show, we [`askill`](/Technicians/ArmorStandAnimations#killing-armor-stands) wavyboi. This important step ensures that we don't have duplicate copies of our armorstand sitting around ambiently, causing unnecessary load on clients who aren't in `region`. The cleanup show is where you should clean up after all your effects -- remove traincarts, kill armorstands, stop lasers/spotlights/etc. The cleanup show should set the stage for the `setup` show to be run again when next a player enters `region`.



## Loading Region Shows
Since region shows can be located in any show file directory, you need to run a command to "register" them so they start listening on their region. You can do that by the following:

- `/loadregionshows <folderPath>`
	- `folderPath` should be the path to a folder in show filesystem -- for example, `myProject/queue/waving_animatronic`. The system will recursively walk through all subdirectories looking for files ending in `_regionshowschema.yml`, and interpret those as Region Show Schemas.
  - The command will spit out an error if there is a problem with your Region Show Schema *OR* if there is a problem parsing any of the show files that it references.

  - If you make a change to your `setup`, `loop`, or `cleanup` shows, you'll have to run `/loadregionshows <folderPath>` again to stop/reload the Schema in order for the changes in the shows to take effect.

The `/regionshows` command will spit out a list of regions and their associated region show schemas, as well as what state they're in (`IDLE`, `SETUP`, `LOOP`, or `CLEANUP`).

## "Un"Loading Region Shows
If you want to turn off an existing region show, go into the Schema file and set `ignore: true` at the top of the file, like so:
```yaml
ignore: true
region: "myRegion"
setup: 'myProject/queue/waving_animatronic/setup'
loop: 'path/to/loopShow/that/doesnt/need/a/delay'
cleanup: 'myProject/queue/waving_animatronic/cleanup'
```
Then run `/loadregionshows <folderPath>`. The Region Show Schema file will be ignored by the loader.


## Advanced Region Show Schemas
### What if I don't need a setup and cleanup show?
You *do* still need to provide one in your schema. However, you can pass in a blank show file (literally, an empty `.yml` file) in the `setup` and/or `cleanup` fields if they aren't relevant to your usecase.

On most servers, there should be an `Inf` folder in the main directory with a show file titled `Blank.yml` which you are recommended to use rather than creating multiple new blank files for whatever project you're working on. The syntax would be `Inf/Blank`.


### Multiple Loop Shows with different Delays
If you have several animations in a `region` that finish their animation cycles at different rates (for example, think of the first scene of *it's a small world*) and each require different delays, you can substitute your `loop` show string for a list of loop shows in your Schema like so:
```yaml
region: 'testregion'
setup: 'path/to/setup'
loop: 
  - name: 'path/to/loop1'
    delay: 10
  - name: 'path/to/loop2'
    delay: 20
cleanup: 'path/to/cleanup'
```

### Setup Delay
Sometimes, you might want to a delay between when your [`setup` show](#setup-show-example) completes and when your [`loop` show](#loop-show-example) starts looping. You can accomplish this in the Schema file very similarly to how you add a delay for your loop show:
```yaml
region: 'testregion'
setup:
  name: 'path/to/setup'
  delay: 20
loop: 'path/to/loop'
cleanup: 'path/to/cleanup'
```
The setup delay is optional and will default to `0` ticks.

