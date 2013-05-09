import boostPython

planet = boostPython.World()
planet.set('howdy')
assert( planet.greet() == 'howdy' )
