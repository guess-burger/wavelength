# wavelength

FIXME: my new application.


## Running

Spin up a repl using `clj -M:repl/run` and that _should_ start the server on 8080

You probably need a cljs build step _somewhere_ but using `clj -M:repl/fig` for now to run fig-wheel

## TODO

### NEXT
* [X] Psychic Phase
  * [X] Pick a psychic
    * [X] disconnects (to fix the disconnects bug). Go back to pick
  * [X] let them pick a card
  * [X] let them add a clue
* [X] Guessing Phase
* [X] L/R Phase
* [X] Figure out scores and move on to the next round
* [X] Make it look a little better
* [X] Figure out if someone has won
  * [X] play again
  * [X] change teams
* [X] Sudden death
* [X] Quit/rejoin state bugs (so far)
* [X] Heroku?
  * deploying
  * keeping the ws alive for longer than 1min
* [_] a proper style sheet
  * [_] maybe even a container div
* [_] Allow spectators to join mid-game
  * This one will need the full state for spectators collecting and sending out
  * Do lobbys continue to live in the lobby atom?
    * What happen when you try to join one right now?
* [_] Clean up and document stately
* [_] Make a custom slider


### Things to keep an eye on

* spectators should be able to join at any point really...
  * So does that mean we need to break joining and the lobbies atom out of the lobbie ns?
  * TODO: Let's ignore this for now and handle it later once things re working

### Bugs

* No inputs to stately cause hot-loop if there is a default that recurs
  * I mean what does it mean have to have no inputs... that seems an error
  * Actually... is it that the real problem or it that it now null since they are closed
    * probably need to figure out leaving asap and make it reusable 
* Deck is an infinite sequence that which is fine until println tries to walk the entire thing
  * Will a logging framework make the same mistake? How can you get around it? 
  * Logging frameworks don't handle this!!
* Long wavelength prompts render a bit strange (GH issue #2)

...

## License

Copyright © 2022 Gavinesberger

Distributed under the Eclipse Public License version 1.0.
