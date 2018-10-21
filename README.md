# MCPConfig-CSV-mappings
**Unofficial** extension for [MCPConfig](https://github.com/MinecraftForge/MCPConfig) which supports CSV mappings like the ones available at http://export.mcpbot.bspk.rs/. These mappings have to be applied after classes and their members have been renamed using a `.srg` / `.tsrg` file.

This project is not very well structured at the moment and only intended as a temporary solution. Feedback is nevertheless appreciated.

# Content
This project includes the following Gradle tasks. Each task tries to do as much as possible by only logging warnings or errors when one of multiple items which should be processed fails, e.g., when mappings could not be applied to a file.

## [CsvDownloaderTask](https://github.com/Marcono1234/MCPConfig-CSV-mappings/blob/master/csv_mappings/downloader/CsvDownloaderTask.java)
Downloads zipped CSV mappings and extracts them.

### Parameters
| Name | Type | Default value | Description |
| - | - | - | - |
| outDirectory | `Path` | - | Where CSV files should be extracted to |
| version | `MinecraftVersion` | - | The Minecraft release version for which mappings should be downloaded |
| mappingSelectionType | `SelectionType` | `SelectionType.STABLE_ELSE_SNAPSHOT` | Defines which mapping type should be used |
| shouldIncludeDoc | `boolean` | `true` | Whether the choosen mappings should include documentation |
| shouldAllowOlderMappings | `boolean` | `true` | Whether older mappings should be used if no matching ones are found for the specified Minecraft version |

## [CsvApplierTask](https://github.com/Marcono1234/MCPConfig-CSV-mappings/blob/master/csv_mappings/applier/CsvApplierTask.java)
### Parameters
| Name | Type | Default value | Description |
| - | - | - | - |
| projectType | `ProjectType` | - | Defines the type of the project (Client, Server or Joined); this determines which mappings are used |
| csvDirectory | `Path` | - | Directory containing the CSV mapping files |
| srcDirectory | `Path` | - | Directory containing the source code files which should be mapped |
| srcOutDirectory | `Path` | - | Directory to which the mapped source code file should be written |

## Description
Applies the CSV mappings to source code files and writes the mapped files to a separate directory. The directories must not be descendants.

If none of the files `fields.csv`, `methods.csv` or `params.csv` is present this task fails.

# Usage with MCPConfig
Make sure that fernflower uses 4 spaces as indentation, otherwise documentation will not be inserted. The [MCPConfig issue #28](https://github.com/MinecraftForge/MCPConfig/issues/28) suggests to use this by default. To change the indentation when running MCPConfig for Minecraft version `<version>`, do the following:

1. Open the file `versions/<version>/config.json` with a text editor
1. Add to the array `fernflower` > `args` the element <code>"-ind=&nbsp;&nbsp;&nbsp;&nbsp;"</code>:

       {
           "fernflower": {
               "args": [..., "-ind=    ", ...],
               ...
           },
           ...
       }

1. Decompile Minecraft again

Download from this project the folder [`csv_mappings`](https://github.com/Marcono1234/MCPConfig-CSV-mappings/tree/master/csv_mappings) with all its contents and place it in the folder `buildSrc/src/main/java` of MCPConfig.

The file [`build.gradle`](https://github.com/Marcono1234/MCPConfig-CSV-mappings/blob/master/build.gradle) can be used as replacement for MCPConfig's `build.gradle` file. Alternatively the patch [`build.gradle.patch`](https://github.com/Marcono1234/MCPConfig-CSV-mappings/blob/master/build.gradle.patch) has to be used (created for MCPConfig commit a4aeb1437e4194e532a36a40b68c52676b3ca36c).

This adds the following tasks, with `<version>` being the respective Minecraft version supported by MCPConfig. It is recommended to run them with `--info --stacktrace` to be able to debug errors more easily.

## `<version>:downloadCsvs`
Downloads the CSV mappings for the version to the folder `versions/<version>/mapping_csvs`. This task runs with the default values described above.

This is only available for Minecraft releases or pre-releases, but not for snapshots. For pre-releases the mappings for the respective release are used, e.g., `1.13.2-pre1` -> `1.13.2`.

## `<version>:project<project_type>ApplyCsvs`
With `<project_type>` being either `Client`, `Server` or `Joined`.

Applies the mapping CSVs from the folder `versions/<version>/mapping_csvs` to the source code in `versions/<version>/projects/<project_type>/src/main/java` and writes the mapped files to `versions/<version>/projects/<project_type>/src_csv/main/java`.

This task has to be used after patches have been applied, it does not work the other way around.

It is recommended to rename the `src` afterwards to for example `src_original` and then `src_csv` to `src`. This way you can use the mapped code in your IDE and when you want to use newer mappings delete the `src` folder and rename the `src_original` folder back.
