# Renders docs/demo.gif with vhs (https://github.com/charmbracelet/vhs):
#
#   1. start Postgres and the app, seeded:
#        ESCALADE_SEED_DEMO=true ./mvnw spring-boot:run
#   2. vhs scripts/demo.tape
#
# The app must already be running — this records the demo script against it.

Output docs/demo.gif

Set FontSize 16
Set Width 1100
Set Height 760
Set Padding 18
Set Theme "Catppuccin Mocha"
Set PlaybackSpeed 1.0

Type "./scripts/demo.sh"
Enter

# The script sleeps between beats so the escalation is visible as it happens.
Sleep 22s
