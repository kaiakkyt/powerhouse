# Powerhouse changelog!
Powerhouse's full changelog!

## v1.00.0
- Initial release of Powerhouse!
- Still in development so new features added very fast!
- Final plugin to use the x.xx.xx versioning system!

## v1.10.0
- Actual exponential-linear scaling system with **some** boundaries!
- Better Java handling for your server.
- Better debug logging.
- 1.7.x and 1.8.x support dropped due to Bukkit API problems.
- Stronger detecting and checks on tnt and other sources.
- Fixed the debug command!

## v1.11.0
- Made the TNT canceller more powerful and efficient.
- Made the AI management more efficient.
- Faster and better hopper calculation and limiting.
- Better server view distance controller.
- Fixed Java memory leaking and JVM monitoring.

## v1.11.1
- Fixed the TNT physics canceller, can tell if legit or illegitimate TNT explosion.

## v1.12.0
- Changed entity clearing by a little bit.
- Changed some internal coding.
- Added particle culling.
- Added an efficient and smart anti-book lagging (book bans/crashers) exploit system!

## v1.13.0
- Fixed and rewritten the Discord webhook feature.
- Added projectile and better particle culling managing.
- Rewritten ```EntityPusher``` to be **EXTREMELY** more efficient than previous.
- Fixed redstone culling, it wasn't allowing redstone to be **used at all**. Occasionally stops working but will be patched fully eventually!
- Actually put thought into stress-testing a server for Powerhouse! :)

## v1.13.1
- Added **way** better memory leaking prevention.
- Added mini chunk pre-loading, only works when the server is empty, **may cause some lag** on startup. Set a world border so it doesnt keep infinitely generating!

## v1.13.2
- Rewritten the way MSPT is measured, for better accuracy.
- Currently rewriting and finishing the way chunk pre-loading is working.
- More debugging info.

## v1.13.3
- Finished fixing the way redstone is being throttled and culled!
- Nothing else really new.

## v1.13.4
- Updated the Powerhouse API to match with the newer code.
- Rewrote the way MSPT is measured again for actual accuracy.
- Added an overnight mode to the chunk pre-loader. Also made it more "idiot-proof".

## v1.13.5
- Updated the item clump and remover system.
- Added ```/powerhouse reload``` command to reload your configs.
- Added new configuration settings in your default ```config.yml```.
- This is the final 1.13.x version!

## v1.14.0
- Introducing a web dashboard for lag monitor, Folia not supported due to technical issues. Hosted by your server on a different port.
- Added better Java GC lag spike handling.
- Removed the chunk preloader, was too laggy. While yes that's the point, 90.83% of the thread is crazy + it wasn't meant to last anyways.
- Fixed and patched up a Discord webhook bug.
- Some internal code cleaning up, makes it easier for me.

## v1.14.1
- Removed the Discord webhook feature, was too buggy and unused.

## v1.14.2
- The web dashboard gets a major revamp, now looks and acts 5x cooler and more server-side validation for safety!
- Small internal restructuring.

## v1.15.0
- Fixed data gathering for Folia servers, also fixed the bug where used to incorrectly detect Paper servers as Folia.
- The web dashboard finally has a name, PowerCapturer!
- Updated PowerCapturer, pretty new changes.
- Updated the server controller, since was outdated.
- More internal restructuring, with more re-programming instead.

## v1.15.1
- Finishing the bridge for the Powerhouse-Axior support, added Axior support to Powerhouse now.
- Nothing much new.

## v1.20.0
- Working on making Powerhouse proxy aware of Bungee and Velocity.
- Bringing back *some* hardware statistics from LagStabilizer!
- **Major** internal restructuring, no longer just divided by sync and async and now 5x more modular!
- Added a few aliases to the ```/powerhouse``` command.
- Updated the leak prevention, due to continously rising RAM usage that still happened.
- Removed the token verification from PowerCapturer.