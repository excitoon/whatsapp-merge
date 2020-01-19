### How to build

```
gradle build
```

### How to run

Data from new database shall be merged into old database (not overwriting it, but producing `output.db` in current directory). Application creates additional file `temp.db` during the process.

```
java -jar build/libs/mymerge-1.0-SNAPSHOT.jar -old old.db -new new.db
```

### Copying

Original project is hosted here: https://github.com/namnoops/merjapp

Other contributors:
- p4r4d0x86
- BenChampion
